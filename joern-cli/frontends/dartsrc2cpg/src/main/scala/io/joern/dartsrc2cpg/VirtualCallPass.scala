package io.joern.dartsrc2cpg

import io.joern.x2cpg.frontendspecific.DartLanguage
import io.joern.x2cpg.passes.base.MethodStubCreator
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes, Properties}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import scala.collection.mutable
import ujson.Value

private[dartsrc2cpg] object VirtualCallPass {
  val ReceiverErasureTag  = "dart.receiver.erasure"
  val RequiredReceiverTag = "dart.receiver.required"
}

class VirtualCallPass(cpg: Cpg, units: Seq[Value]) extends CpgPass(cpg) {
  private def text(value: Value, key: String, fallback: String = ""): String =
    value.obj.get(key).flatMap(_.strOpt).getOrElse(fallback)
  private def values(value: Value, key: String): Seq[Value] = value.obj.get(key).map(_.arr.toSeq).getOrElse(Nil)
  private val symbols           = units.flatMap(values(_, "symbols")).map(symbol => text(symbol, "id") -> symbol).toMap
  private var linkedCalls       = 0
  private var missingTargets    = 0
  private val externalTargets   = mutable.Set.empty[String]
  private val implicitAccessors = mutable.Set.empty[String]

  def report: Value = ujson.Obj(
    "approximation"              -> "analyzer-hierarchy-target-union",
    "resolvedCalls"              -> linkedCalls,
    "withoutObservedTarget"      -> missingTargets,
    "externalTargets"            -> externalTargets.size,
    "unmodeledImplicitAccessors" -> implicitAccessors.size
  )

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val declarations = units.flatMap(values(_, "nodes")).filter(node => node.obj.contains("virtualTargets"))
    val hierarchy    = declarations.map { node =>
      val id = text(node, "declaration")
      id -> (Set(id) ++ values(symbols.getOrElse(id, ujson.Obj()), "superDeclarations").map(_.str))
    }.toMap
    val enums = declarations.filter(node => text(node, "kind") == "EnumDeclaration").map(text(_, "declaration")).toSet
    val implementations = declarations
      .flatMap { node =>
        values(node, "virtualTargets")
          .map(target => text(target, "member") -> (text(node, "declaration"), text(target, "implementation")))
      }
      .groupMap(_._1)(_._2)
    val methods = cpg.method.map(method => method.fullName -> method).toMap
    val stubs   = mutable.Map.empty[String, NewMethod]

    def bound(id: String, visited: Set[String] = Set.empty): String = {
      val symbol = symbols.getOrElse(id, ujson.Obj())
      val next   = text(symbol, "kind") match {
        case "TYPE_PARAMETER" => text(symbol, "boundTypeId")
        case "EXTENSION_TYPE" => text(symbol, "erasedTypeId")
        case _                => ""
      }
      if (next.nonEmpty && !visited(id)) bound(next, visited + id) else id
    }
    def target(id: String): Option[MethodBase] = {
      methods.get(id).filter(_.isExternal).foreach(_ => externalTargets += id)
      methods.get(id).orElse(stubs.get(id)).orElse {
        symbols.get(id).map { symbol =>
          externalTargets += id
          if (
            symbol.obj.get("synthetic").contains(ujson.Bool(true)) && Set("GETTER", "SETTER").contains(
              text(symbol, "kind")
            )
          )
            implicitAccessors += id
          val count = values(symbol, "parameters").size
          val stub  = MethodStubCreator
            .createMethodStub(
              text(symbol, "name"),
              id,
              s"${text(symbol, "returnType", "ANY")}($count)",
              DispatchTypes.DYNAMIC_DISPATCH,
              count + 1,
              diffGraph,
              startWithInst = Some(true)
            )
            .genericSignature(text(symbol, "genericSignature"))
          stubs(id) = stub
          stub
        }
      }
    }
    cpg.call.dispatchTypeExact(DispatchTypes.DYNAMIC_DISPATCH).foreach { call =>
      symbols
        .get(call.methodFullName)
        .filter { symbol =>
          Set("METHOD", "GETTER", "SETTER").contains(text(symbol, "kind")) &&
          symbols
            .get(text(symbol, "owner"))
            .exists(owner => Set("CLASS", "MIXIN", "ENUM").contains(text(owner, "kind")))
        }
        .foreach { _ =>
          val constraints =
            call.receiver
              .flatMap { value =>
                val actual = value.tag
                  .nameExact(VirtualCallPass.ReceiverErasureTag)
                  .value
                  .headOption
                  .orElse(value.propertyOption(Properties.TypeFullName))
                actual.toSeq ++ value.tag.nameExact(VirtualCallPass.RequiredReceiverTag).value
              }
              .map(bound(_))
              .filter(symbols.contains)
              .toSet
          val candidates = implementations
            .getOrElse(call.methodFullName, Nil)
            .filter { case (id, _) =>
              constraints.forall(hierarchy.getOrElse(id, Set.empty))
            }
            .map { case (id, implementation) =>
              val member    = symbols.getOrElse(implementation, ujson.Obj())
              val name      = text(member, "name")
              val owner     = symbols.getOrElse(text(member, "owner"), ujson.Obj())
              val generated = enums(id) && (name == "index" && text(owner, "name") == "Enum" ||
                name == "toString" && text(owner, "name") == "Object")
              if (generated) s"$id:<enum:$name>" else implementation
            }
          val declaration    = symbols(call.methodFullName)
          val abstractSource = declaration.obj.get("abstract").contains(ujson.Bool(true)) &&
            methods.get(call.methodFullName).exists(method => !method.isExternal)
          val fallback = if (abstractSource) Nil else Seq(call.methodFullName)
          val resolved = (fallback ++ candidates).distinct.sorted.flatMap(target)
          if (resolved.isEmpty) missingTargets += 1
          resolved.foreach {
            case method: Method    => diffGraph.addEdge(call, method, EdgeTypes.CALL)
            case method: NewMethod => diffGraph.addEdge(call, method, EdgeTypes.CALL)
          }
          val tag = NewTag().name(DartLanguage.ResolvedDispatchTag).value("analyzer")
          diffGraph.addNode(tag)
          diffGraph.addEdge(call, tag, EdgeTypes.TAGGED_BY)
          linkedCalls += 1
        }
    }
  }
}
