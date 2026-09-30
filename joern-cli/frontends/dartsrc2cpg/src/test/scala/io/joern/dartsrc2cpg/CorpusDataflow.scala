package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.dataflowengineoss.queryengine.{EngineContext, EngineConfig}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import ujson.Value

private[dartsrc2cpg] object CorpusDataflow {
  implicit val resolver: ICallResolver = NoResolve

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

  def audit(cpg: Cpg, probes: Seq[Value], semantics: Semantics): Value = {
    semantics.initialize(cpg)
    ujson.Arr.from(probes.map { probe =>
      implicit val context: EngineContext = EngineContext(
        semantics = semantics,
        config = EngineConfig(maxCallDepth = probe.obj.get("maxCallDepth").map(_.num.toInt).getOrElse(4))
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
      val passed = sources.size == 1 && sinks.size == 1 && paths.nonEmpty == probe("expected").bool && via
      ujson.Obj(
        "id"            -> probe("id"),
        "expected"      -> probe("expected"),
        "passed"        -> passed,
        "sources"       -> sources.size,
        "sinks"         -> sinks.size,
        "paths"         -> paths.size,
        "viaSatisfied"  -> via,
        "elapsedMillis" -> ujson.Num((System.nanoTime() - started) / 1000000.0),
        "witnesses"     -> ujson.Arr.from(
          paths
            .take(3)
            .map(path =>
              ujson.Arr.from(
                path.elements.map(n =>
                  ujson.Obj(
                    "method" -> (n match { case cfg: CfgNode => cfg.method.fullName; case _ => "" }),
                    "code"   -> n.code,
                    "line"   -> n.lineNumber.getOrElse(-1)
                  )
                )
              )
            )
        )
      )
    })
  }
}
