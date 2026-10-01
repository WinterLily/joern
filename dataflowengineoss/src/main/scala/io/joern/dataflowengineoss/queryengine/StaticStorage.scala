package io.joern.dataflowengineoss.queryengine

import io.joern.dataflowengineoss.passes.reachingdef.ExitRouting
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
      enum ExitMode { case Normal, Thrown }
      case class Scope(
        stack: List[Call],
        depth: Int,
        pendingExit: Option[ExitMode],
        continuations: List[Option[ExitMode]]
      )
      case class Position(node: CfgNode, scope: Scope, evidence: Vector[PathElement], exceptional: Boolean)
      val seen                                   = mutable.HashSet.empty[(CfgNode, Scope, Boolean)]
      val pending                                = mutable.ArrayDeque.empty[Position]
      val definitions                            = mutable.ArrayBuffer.empty[ReachableByTask]
      val escaping                               = mutable.Map.empty[Method, List[CfgNode]]
      val ancestry                               = mutable.Map.empty[CfgNode, Set[AstNode]]
      val exitModes                              = mutable.Map.empty[CfgNode, Set[String]]
      def ancestors(node: CfgNode): Set[AstNode] = ancestry.getOrElseUpdate(node, Iterator.single(node).inAst.toSet)
      def modes(node: CfgNode): Set[String]      =
        exitModes.getOrElseUpdate(node, node.tag.nameExact("dart.cfg.exit").value.toSet)
      def inCleanup(node: CfgNode): Boolean = ancestors(node)
        .collect { case control: ControlStructure => control }
        .exists(_._finallyBodyOut.cast[AstNode].exists(ancestors(node).contains))
      def cleanups(node: CfgNode): Set[AstNode] = ancestors(node)
        .collect { case control: ControlStructure => control }
        .flatMap(_._finallyBodyOut.cast[AstNode])
        .filterNot(ancestors(node).contains)

      def enqueue(node: CfgNode, scope: Scope, evidence: Vector[PathElement], exceptional: Boolean = false): Unit = {
        if (config.maxCallDepth != -1 && scope.depth > config.maxCallDepth)
          config.diagnostics.foreach(_.record("call-depth"))
        else pending.append(Position(node, scope, evidence, exceptional))
      }

      def before(node: CfgNode, scope: Scope, evidence: Vector[PathElement]): Unit = {
        val catches = ancestors(node).collect {
          case handler: ControlStructure if handler.controlStructureType == ControlStructureTypes.CATCH => handler
        }
        node._cfgIn.cast[CfgNode].foreach { predecessor =>
          val entersCatch = ExitRouting
            .handlerOwner(predecessor, predecessor.method)
            .exists(owner => owner._catchBodyOut.cast[AstNode].exists(catches.contains))
          val entersCleanup = cleanups(predecessor).exists(ancestors(node).contains)
          if (entersCatch) enqueue(predecessor, scope, evidence, exceptional = true)
          else if (!entersCleanup) enqueue(predecessor, scope, evidence)
          else
            predecessor match {
              case _: Return =>
                if (!scope.pendingExit.contains(ExitMode.Thrown))
                  enqueue(predecessor, scope.copy(pendingExit = None), evidence)
              case control: ControlStructure if control.controlStructureType == ControlStructureTypes.THROW =>
                if (!scope.pendingExit.contains(ExitMode.Normal))
                  enqueue(predecessor, scope.copy(pendingExit = None), evidence, exceptional = true)
              case call: Call
                  if inCleanup(call) && modes(call).exists(kind => kind == "normal.after" || kind == "throw.after") =>
                for (
                  mode <- scope.pendingExit.toList match {
                    case Nil      => List(ExitMode.Normal, ExitMode.Thrown)
                    case selected => selected
                  }
                ) {
                  val label = if (mode == ExitMode.Normal) "normal.after" else "throw.after"
                  if (modes(call).contains(label)) enqueue(call, scope.copy(pendingExit = Some(mode)), evidence)
                }
                if (!scope.pendingExit.contains(ExitMode.Normal) && modes(call).contains("throw.before"))
                  enqueue(call, scope.copy(pendingExit = None), evidence, exceptional = true)
              case call: Call =>
                if (!scope.pendingExit.contains(ExitMode.Thrown))
                  enqueue(call, scope.copy(pendingExit = None), evidence)
                if (!scope.pendingExit.contains(ExitMode.Normal))
                  enqueue(call, scope.copy(pendingExit = None), evidence, exceptional = true)
              case _ =>
                if (!scope.pendingExit.contains(ExitMode.Thrown))
                  enqueue(predecessor, scope.copy(pendingExit = None), evidence)
            }
        }
      }

      def exits(method: Method, scope: Scope, evidence: Vector[PathElement], exceptional: Boolean): Unit = {
        if (!modes(method.methodReturn).contains("complete"))
          config.diagnostics.foreach(_.record("static-storage-exit-metadata"))
        else {
          method.methodReturn._cfgIn.cast[CfgNode].foreach { node =>
            val kinds = modes(node)
            if (kinds.contains("normal.after") && kinds.contains("throw.after"))
              config.diagnostics.foreach(_.record("static-storage-joined-exits"))
            if (!exceptional && kinds.contains("normal.after")) {
              val demand = Option.when(inCleanup(node) && !node.isInstanceOf[Return])(ExitMode.Normal)
              enqueue(node, scope.copy(pendingExit = demand), evidence)
            }
            if (exceptional && kinds.contains("throw.after"))
              enqueue(node, scope.copy(pendingExit = Some(ExitMode.Thrown)), evidence)
            if (exceptional && kinds.contains("throw.before"))
              enqueue(node, scope.copy(pendingExit = None), evidence, exceptional = true)
          }
          if (exceptional)
            escaping
              .getOrElseUpdate(method, ExitRouting.escaping(method))
              .collect { case call: Call if cleanups(call).isEmpty && !modes(call).contains("throw.before") => call }
              .foreach { call =>
                if (call.name.startsWith("<operator>"))
                  config.diagnostics.foreach(_.record("static-storage-implicit-exception-effects"))
                else enqueue(call, scope.copy(pendingExit = None), evidence, exceptional = true)
              }
        }
      }

      val initial = Scope(result.callSiteStack, result.callDepth, None, result.callSiteStack.map(_ => None))
      before(read, initial, Vector.empty)
      while (pending.nonEmpty && seen.size < config.maxStaticStorageNodes) {
        val Position(node, scope, evidence, exceptional) = pending.removeHead()
        val stack                                        = scope.stack
        if (seen.add((node, scope, exceptional))) node match {
          case assignment: Call
              if !exceptional && assignment.name == Operators.assignment &&
                assignment.argument.filter(_.argumentIndex == 1).cast[CfgNode].exists(key(_).contains(storage)) =>
            assignment.argument.filter(_.argumentIndex == 2).cast[CfgNode].foreach { value =>
              val fingerprint = TaskFingerprint(value, stack, scope.depth, fieldDemand = result.path.head.fieldDemand)
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
                val caller = Scope(
                  tail,
                  scope.depth - 1,
                  scope.continuations.headOption.getOrElse(None),
                  scope.continuations.drop(1)
                )
                before(call, caller, Vector(PathElement(method, stack, visible = false)) ++ evidence)
              case Nil =>
                val callers = method.callIn(NoResolve).toVector
                if (callers.size > config.maxArgsToAllow) config.diagnostics.foreach(_.record("parameter-arguments"))
                else
                  callers.foreach(call =>
                    before(
                      call,
                      Scope(Nil, scope.depth + 1, None, Nil),
                      Vector(PathElement(method, Nil, visible = false)) ++ evidence
                    )
                  )
            }
          case thrown: ControlStructure if thrown.controlStructureType == ControlStructureTypes.THROW =>
            before(
              thrown,
              scope,
              Vector(
                PathElement(thrown, stack, visible = false, outEdgeLabel = "<STATIC_STORAGE_EXCEPTION>")
              ) ++ evidence
            )
          case call: Call if !call.name.startsWith("<operator>") =>
            if (call.inAstMinusLeaf.isControlStructure.controlStructureTypeExact(ControlStructureTypes.TRY).nonEmpty)
              config.diagnostics.foreach(_.record("static-storage-exception-state"))
            val targets = NoResolve.getCalledMethods(call).toVector
            if (targets.isEmpty || targets.exists(method => method.isExternal || method.start.isStub.nonEmpty)) {
              config.diagnostics.foreach(_.record("static-storage-external-effects"))
              before(call, scope, evidence)
            }
            targets.filterNot(method => method.isExternal || method.start.isStub.nonEmpty).foreach { method =>
              val callee = Scope(call :: stack, scope.depth + 1, None, scope.pendingExit :: scope.continuations)
              val path   = Vector(
                PathElement(
                  method.methodReturn,
                  callee.stack,
                  visible = false,
                  outEdgeLabel = if (exceptional) "<STATIC_STORAGE_EXCEPTION>" else ""
                ),
                PathElement(call, stack)
              ) ++ evidence
              exits(method, callee, path, exceptional)
            }
          case _: Call if exceptional =>
            config.diagnostics.foreach(_.record("static-storage-implicit-exception-effects"))
          case call: Call
              if call.name == "<operator>.isInitialized" &&
                call.argument.cast[CfgNode].exists(key(_).contains(storage)) =>
            config.diagnostics.foreach(_.record("static-storage-initialization-state"))
            before(call, scope, evidence)
          case control: ControlStructure if control.controlStructureType == ControlStructureTypes.TRY =>
            config.diagnostics.foreach(_.record("static-storage-exception-state"))
            before(control, scope, evidence)
          case _ => before(node, scope, evidence)
        }
      }
      if (pending.nonEmpty) config.diagnostics.foreach(_.record("static-storage-search"))
      definitions.toVector
    }
  }
}
