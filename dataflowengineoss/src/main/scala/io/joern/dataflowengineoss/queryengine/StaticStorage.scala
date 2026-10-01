package io.joern.dataflowengineoss.queryengine

import io.shiftleft.codepropertygraph.generated.{Cpg, ControlStructureTypes, ModifierTypes, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

private[queryengine] object StaticStorage {
  case class Key(owner: String, member: String)

  def key(node: CfgNode): Option[Key] = node match {
    case access: Call if io.joern.dataflowengineoss.isDart(access) && access.name == Operators.fieldAccess =>
      for {
        receiver <- access.argument.filter(_.argumentIndex == 1).collectAll[Identifier].headOption
        field    <- access.argument.filter(_.argumentIndex == 2).collectAll[FieldIdentifier].headOption
        if receiver.name == receiver.typeFullName
        cpg = new Cpg(access.graph)
        owner <- (cpg.typeDecl.fullNameExact(receiver.name).cast[AstNode] ++
          cpg.namespaceBlock.fullNameExact(receiver.name).cast[AstNode]).find { owner =>
          owner.astChildren.collectAll[Member].nameExact(field.canonicalName).exists { member =>
            member.modifier.modifierTypeExact(ModifierTypes.STATIC).nonEmpty
          }
        }
      } yield Key(receiver.name, field.canonicalName)
    case _ => None
  }

  def readKey(node: CfgNode): Option[Key] = key(node).filter { _ =>
    node._astIn.collectAll[Call].forall { parent =>
      parent.name != "<operator>.isInitialized" &&
      !(parent.name == Operators.assignment && parent.argument.exists(arg => arg.argumentIndex == 1 && arg == node))
    }
  }

  def tasks(result: ReachableByResult, config: EngineConfig): Vector[ReachableByTask] = {
    val read = result.path.head.node.asInstanceOf[CfgNode]
    readKey(read).toVector.flatMap { storage =>
      case class Position(node: CfgNode, stack: List[Call], depth: Int, evidence: Vector[PathElement])
      val seen        = mutable.HashSet.empty[(CfgNode, List[Call], Int)]
      val pending     = mutable.ArrayDeque.empty[Position]
      val definitions = mutable.ArrayBuffer.empty[ReachableByTask]

      def enqueue(node: CfgNode, stack: List[Call], depth: Int, evidence: Vector[PathElement]): Unit = {
        if (config.maxCallDepth != -1 && depth > config.maxCallDepth)
          config.diagnostics.foreach(_.record("call-depth"))
        else pending.append(Position(node, stack, depth, evidence))
      }

      def before(node: CfgNode, stack: List[Call], depth: Int, evidence: Vector[PathElement]): Unit =
        node._cfgIn.cast[CfgNode].foreach(enqueue(_, stack, depth, evidence))

      before(read, result.callSiteStack, result.callDepth, Vector.empty)
      while (pending.nonEmpty && seen.size < config.maxStaticStorageNodes) {
        val Position(node, stack, depth, evidence) = pending.removeHead()
        if (seen.add((node, stack, depth))) node match {
          case assignment: Call
              if assignment.name == Operators.assignment && assignment.argument
                .filter(_.argumentIndex == 1)
                .cast[CfgNode]
                .exists { lhs =>
                  key(lhs).contains(storage)
                } =>
            assignment.argument.filter(_.argumentIndex == 2).cast[CfgNode].foreach { value =>
              val fingerprint = TaskFingerprint(value, stack, depth, fieldDemand = result.path.head.fieldDemand)
              val store       = PathElement(
                assignment,
                stack,
                outEdgeLabel = "<STATIC_STORAGE>",
                fieldDemand = result.path.head.fieldDemand
              )
              definitions.append(
                ReachableByTask(result.taskStack :+ fingerprint, Vector(store) ++ evidence ++ result.path)
              )
            }
          case method: Method =>
            stack match {
              case call :: tail =>
                before(call, tail, depth - 1, Vector(PathElement(method, stack, visible = false)) ++ evidence)
              case Nil =>
                val callers = method.callIn(NoResolve).toVector
                if (callers.size > config.maxArgsToAllow) config.diagnostics.foreach(_.record("parameter-arguments"))
                else
                  callers.foreach(call =>
                    before(call, Nil, depth + 1, Vector(PathElement(method, Nil, visible = false)) ++ evidence)
                  )
            }
          case thrown: ControlStructure if thrown.controlStructureType == ControlStructureTypes.THROW =>
          case call: Call if !call.name.startsWith("<operator>")                                      =>
            if (call.inAstMinusLeaf.isControlStructure.controlStructureTypeExact(ControlStructureTypes.TRY).nonEmpty)
              config.diagnostics.foreach(_.record("static-storage-exception-state"))
            val targets = NoResolve.getCalledMethods(call).toVector
            if (targets.isEmpty || targets.exists(method => method.isExternal || method.start.isStub.nonEmpty)) {
              config.diagnostics.foreach(_.record("static-storage-external-effects"))
              before(call, stack, depth, evidence)
            }
            targets.filterNot(method => method.isExternal || method.start.isStub.nonEmpty).foreach { method =>
              val calleeStack = call :: stack
              val path        = Vector(
                PathElement(method.methodReturn, calleeStack, visible = false),
                PathElement(call, stack)
              ) ++ evidence
              before(method.methodReturn, calleeStack, depth + 1, path)
            }
          case call: Call
              if call.name == "<operator>.isInitialized" && call.argument
                .cast[CfgNode]
                .exists(key(_).contains(storage)) =>
            config.diagnostics.foreach(_.record("static-storage-initialization-state"))
            before(call, stack, depth, evidence)
          case control: ControlStructure if control.controlStructureType == ControlStructureTypes.TRY =>
            config.diagnostics.foreach(_.record("static-storage-exception-state"))
            before(control, stack, depth, evidence)
          case _ => before(node, stack, depth, evidence)
        }
      }
      if (pending.nonEmpty) config.diagnostics.foreach(_.record("static-storage-search"))
      definitions.toVector
    }
  }
}
