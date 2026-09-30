package io.joern.dataflowengineoss.passes.reachingdef

import io.shiftleft.codepropertygraph.generated.ControlStructureTypes
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

object ExitRouting {
  private def cleanups(origin: CfgNode, method: Method): Set[AstNode] = {
    val ancestors = Iterator.single(origin).inAstMinusLeaf.takeWhile(_ != method).toSet
    ancestors
      .collect { case control: ControlStructure => control }
      .flatMap(_._finallyBodyOut.cast[AstNode])
      .filterNot(ancestors.contains)
  }

  private def cleanupBoundary(origin: CfgNode, node: CfgNode, method: Method): Option[AstNode] =
    Iterator.single(node).inAstMinusLeaf.takeWhile(_ != method).find(cleanups(origin, method).contains)

  private def handledInsideCleanup(origin: CfgNode, thrown: ControlStructure, method: Method): Boolean = {
    cleanupBoundary(origin, thrown, method).exists { cleanup =>
      val withinCleanup  = Iterator.single(thrown).inAstMinusLeaf.takeWhile(_ != cleanup).toList
      val protectedNodes = (thrown :: withinCleanup).toSet
      withinCleanup.collect { case control: ControlStructure => control }.exists { control =>
        control._tryBodyOut.cast[AstNode].exists(protectedNodes.contains) &&
        control._catchBodyOut.cast[AstNode].exists {
          case handler: ControlStructure if handler.controlStructureType == ControlStructureTypes.CATCH =>
            handler.condition.isLiteral.codeExact("true").nonEmpty
          case _ => false
        }
      }
    }
  }

  private def jumpLeavesCleanup(origin: CfgNode, jump: ControlStructure, method: Method): Boolean = {
    cleanupBoundary(origin, jump, method).exists { cleanup =>
      val argument = jump._jumpArgumentOut.cast[AstNode].headOption.orElse(jump.astChildren.order(1).headOption)
      argument match {
        case Some(label: JumpLabel) =>
          !method.ast.collect { case target: JumpTarget if target.name == label.name => target }.exists { target =>
            Iterator.single(target).inAstMinusLeaf.contains(cleanup)
          }
        case _ =>
          val levels       = argument.collect { case value: Literal => value.code.toInt }.getOrElse(1)
          val localTargets =
            Iterator.single(jump).inAstMinusLeaf.takeWhile(_ != cleanup).isControlStructure.count { control =>
              control.controlStructureType match {
                case ControlStructureTypes.FOR | ControlStructureTypes.WHILE | ControlStructureTypes.DO       => true
                case ControlStructureTypes.SWITCH if jump.controlStructureType == ControlStructureTypes.BREAK => true
                case _                                                                                        => false
              }
            }
          levels > localTargets
      }
    }
  }

  def reaches(origin: CfgNode, target: CfgNode, method: Method): Boolean = {
    val seen    = mutable.HashSet.empty[CfgNode]
    val pending = mutable.ArrayDeque.from(origin._cfgOut.cast[CfgNode])
    var reached = false
    while (pending.nonEmpty && !reached) {
      val node = pending.removeHead()
      if (node == target) reached = true
      else if (seen.add(node)) node match {
        case _: Return | _: MethodReturn                                                            =>
        case thrown: ControlStructure if thrown.controlStructureType == ControlStructureTypes.THROW =>
          if (handledInsideCleanup(origin, thrown, method)) pending ++= thrown._cfgOut.cast[CfgNode]
        case jump: ControlStructure
            if jump.controlStructureType == ControlStructureTypes.BREAK ||
              jump.controlStructureType == ControlStructureTypes.CONTINUE =>
          if (!jumpLeavesCleanup(origin, jump, method)) pending ++= jump._cfgOut.cast[CfgNode]
        case _ => pending ++= node._cfgOut.cast[CfgNode]
      }
    }
    reached
  }

  def handlerOwner(origin: CfgNode, method: Method): Option[ControlStructure] = {
    val ancestors      = Iterator.single(origin).inAstMinusLeaf.takeWhile(_ != method).toList
    val protectedNodes = (origin :: ancestors).toSet
    ancestors.collectFirst {
      case control: ControlStructure
          if control._tryBodyOut.cast[AstNode].exists(protectedNodes.contains) &&
            control._catchBodyOut.nonEmpty =>
        control
    }
  }

  def escaping(method: Method): List[CfgNode] = {
    val reachable = mutable.HashSet.empty[CfgNode]
    val pending   = mutable.ArrayDeque[CfgNode](method)
    while (pending.nonEmpty) {
      val node = pending.removeHead()
      if (reachable.add(node)) pending ++= node._cfgOut.cast[CfgNode]
    }
    reachable.toList
      .collect {
        case call: Call                                                                             => call
        case thrown: ControlStructure if thrown.controlStructureType == ControlStructureTypes.THROW => thrown
      }
      .filter { origin =>
        handlerOwner(origin, method).isEmpty && (origin match {
          case _: Call if cleanups(origin, method).isEmpty => true
          case _                                           => reaches(origin, method.methodReturn, method)
        })
      }
  }
}
