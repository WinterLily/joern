package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.dataflowengineoss.queryengine.{EngineContext, EngineConfig, QueryDiagnostics}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import ujson.Value

private[dartsrc2cpg] object CorpusDataflow {
  implicit val resolver: ICallResolver = NoResolve

  private def callEvidence(call: Call): Value = ujson.Obj(
    "nodeId"  -> call.id.toString,
    "name"    -> call.name,
    "target"  -> call.methodFullName,
    "code"    -> call.code,
    "callees" -> ujson.Arr.from(call.callee.toSeq.sortBy(_.fullName).map { method =>
      ujson.Obj("fullName" -> method.fullName, "external" -> method.isExternal)
    })
  )

  private def nodeEvidence(node: AstNode): Value = {
    val record = ujson.Obj(
      "nodeId" -> node.id.toString,
      "label"  -> node.label,
      "method" -> (node match { case cfg: CfgNode => cfg.method.fullName; case _ => "" }),
      "code"   -> node.code,
      "line"   -> node.lineNumber.getOrElse(-1)
    )
    node match {
      case call: Call                    => record("call") = callEvidence(call)
      case ref: MethodRef                => record("referencedMethod") = ref.methodFullName
      case parameter: MethodParameterIn  => record("parameterIndex") = parameter.index
      case parameter: MethodParameterOut => record("parameterIndex") = parameter.index
      case _                             =>
    }
    node match {
      case expression: Expression =>
        val contexts = expression.inCall.map { call =>
          val context = callEvidence(call)
          context("argumentIndex") = expression.argumentIndex
          context
        }.toSeq
        if (contexts.nonEmpty) record("argumentsOf") = ujson.Arr.from(contexts)
      case _ =>
    }
    record
  }

  private def select(cpg: Cpg, selector: Value): List[CfgNode] = {
    val methods = cpg.method
      .isExternal(false)
      .filenameExact(selector("file").str)
      .nameExact(selector("method").str)
      .filter { method =>
        selector.obj.get("owner").forall(owner => method.astParentFullName.endsWith(s":CLASS:${owner.str}"))
      }
      .l
    val nodes: List[CfgNode] = selector("kind").str match {
      case "parameter"  => methods.iterator.parameter.nameExact(selector("name").str).l
      case "return"     => methods.iterator.ast.isReturn.l
      case "fieldWrite" =>
        methods.iterator.ast.isCall
          .nameExact("<operator>.assignment")
          .filter { call =>
            call.argument
              .argumentIndex(1)
              .isCall
              .nameExact("<operator>.fieldAccess")
              .argument(2)
              .isFieldIdentifier
              .canonicalNameExact(selector("name").str)
              .nonEmpty
          }
          .l
      case "call" =>
        methods.iterator.ast.isCall
          .nameExact(selector("name").str)
          .filter { call =>
            selector.obj
              .get("field")
              .forall(field => call.argument.argumentIndex(2).isFieldIdentifier.canonicalNameExact(field.str).nonEmpty)
          }
          .l
    }
    val matching = nodes.filter(n =>
      selector.obj.get("code").forall(_.str == n.code) && selector.obj
        .get("line")
        .forall(line => n.lineNumber.contains(line.num.toInt))
    )
    selector.obj.get("argumentName") match {
      case Some(name) =>
        return matching.collect { case call: Call => call }.iterator.argument.argumentNameExact(name.str).l
      case None =>
    }
    selector.obj.get("argument") match {
      case Some(index) => matching.collect { case call: Call => call }.iterator.argument(index.num.toInt).l
      case None        => matching
    }
  }

  def audit(cpg: Cpg, probes: Seq[Value], semantics: Semantics, witnessLimit: Option[Int] = None): Value = {
    require(witnessLimit.forall(_ >= 0), "Witness limit must be nonnegative")
    require(cpg.metaData.overlays.exists(_ == "dataflowOss"), "Missing dataflow overlay")
    require(probes.map(_("id").str).distinct.size == probes.size, "Duplicate probe IDs")
    semantics.initialize(cpg)
    val results = probes.map { probe =>
      val diagnostics                     = new QueryDiagnostics
      implicit val context: EngineContext = EngineContext(
        semantics = semantics,
        config = EngineConfig(
          maxCallDepth = probe.obj.get("maxCallDepth").map(_.num.toInt).getOrElse(4),
          diagnostics = Some(diagnostics)
        )
      )
      println(s"Dart dataflow: ${probe("id").str}")
      val sources = select(cpg, probe("source"))
      val sinks   = select(cpg, probe("sink"))
      val started = System.nanoTime()
      val paths   = sinks.iterator.reachableByFlows(sources.iterator).l
      val via     = probe.obj
        .get("via")
        .forall(name =>
          paths.exists(_.elements.exists {
            case parameter: MethodParameterIn => parameter.method.name == name.str && !sources.contains(parameter)
            case _                            => false
          })
        )
      val endpointsValid = sources.size == 1 && sinks.size == 1
      val passed         = endpointsValid && paths.nonEmpty == probe("expected").bool && via
      val retained       = witnessLimit.fold(paths)(paths.take)
      val outcome        =
        if (!endpointsValid) "invalid-endpoints"
        else if (!via) "missing-required-callee"
        else if (paths.nonEmpty) "flow-observed"
        else if (diagnostics.limitations.nonEmpty) "inconclusive-query-limits"
        else "no-flow-observed-within-limits"
      ujson.Obj(
        "id"                     -> probe("id"),
        "expected"               -> probe("expected"),
        "source"                 -> probe("source"),
        "sink"                   -> probe("sink"),
        "outcome"                -> outcome,
        "semanticReview"         -> "pending",
        "maxCallDepth"           -> context.config.maxCallDepth,
        "maxArgsToAllow"         -> context.config.maxArgsToAllow,
        "maxOutputArgsExpansion" -> context.config.maxOutputArgsExpansion,
        "limitations"            -> ujson.Arr.from(diagnostics.limitations.toSeq.sorted),
        "searchComplete"         -> diagnostics.limitations.isEmpty,
        "pathSelection"          -> "longest-per-endpoint-pair",
        "alternativeRoutes"      -> "not-returned-by-engine",
        "witnessLimit"           -> witnessLimit.map(ujson.Num(_)).getOrElse(ujson.Null),
        "retainedWitnesses"      -> retained.size,
        "omittedWitnesses"       -> (paths.size - retained.size),
        "passed"                 -> passed,
        "sources"                -> sources.size,
        "sinks"                  -> sinks.size,
        "distinctEndpoints"      -> (endpointsValid && sources.head != sinks.head),
        "paths"                  -> paths.size,
        "viaSatisfied"           -> via,
        "elapsedMillis"          -> ujson.Num((System.nanoTime() - started) / 1000000.0),
        "witnesses"              -> ujson.Arr.from(
          retained
            .map(path => ujson.Arr.from(path.elements.map(nodeEvidence)))
        )
      )
    }
    val byId = results.map(result => result("id").str -> result).toMap
    probes.zip(results).foreach { case (probe, result) =>
      if (!probe("expected").bool) {
        val control   = probe.obj.get("positiveControl").flatMap(id => byId.get(id.str))
        val satisfied = control.exists(c =>
          c("expected").bool && c("passed").bool && c("distinctEndpoints").bool && c("source") == probe("source") &&
            c("maxCallDepth") == result("maxCallDepth")
        )
        result("positiveControl") = probe.obj.getOrElse("positiveControl", ujson.Null)
        result("positiveControlSatisfied") = satisfied
        if (!satisfied) {
          result("passed") = false
          if (result("outcome").str == "no-flow-observed-within-limits")
            result("outcome") = "inconclusive-positive-control"
        }
      }
    }
    ujson.Arr.from(results)
  }
}
