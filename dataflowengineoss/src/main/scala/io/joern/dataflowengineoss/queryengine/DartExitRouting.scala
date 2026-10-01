package io.joern.dataflowengineoss.queryengine

import io.joern.dataflowengineoss.passes.reachingdef.ExitRouting
import io.shiftleft.codepropertygraph.generated.ControlStructureTypes
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

private[queryengine] object DartExitRouting {
  case class Position(node: CfgNode, pendingExit: Option[PendingExit], exceptional: Boolean = false)
}

private[queryengine] class DartExitRouting {
  import DartExitRouting.Position
  private val ancestry                       = mutable.Map.empty[CfgNode, Set[AstNode]]
  private val exitModes                      = mutable.Map.empty[CfgNode, Set[String]]
  def ancestors(node: CfgNode): Set[AstNode] = ancestry.getOrElseUpdate(node, Iterator.single(node).inAst.toSet)
  def modes(node: CfgNode): Set[String]      =
    exitModes.getOrElseUpdate(node, node.tag.nameExact("dart.cfg.exit").value.toSet)
  def complete(method: Method): Boolean = modes(method.methodReturn).contains("complete:2")
  def inCleanup(node: CfgNode): Boolean = ancestors(node)
    .collect { case control: ControlStructure => control }
    .exists(_._finallyBodyOut.cast[AstNode].exists(ancestors(node).contains))
  def cleanups(node: CfgNode): Set[AstNode] = ancestors(node)
    .collect { case control: ControlStructure => control }
    .flatMap(_._finallyBodyOut.cast[AstNode])
    .filterNot(ancestors(node).contains)

  def before(node: CfgNode, pendingExit: Option[PendingExit]): Vector[Position] = {
    val catches = ancestors(node).collect {
      case handler: ControlStructure if handler.controlStructureType == ControlStructureTypes.CATCH => handler
    }
    node._cfgIn
      .cast[CfgNode]
      .flatMap { predecessor =>
        val entersCatch = ExitRouting
          .handlerOwner(predecessor, predecessor.method)
          .exists(owner => owner._catchBodyOut.cast[AstNode].exists(catches.contains))
        val entersCleanup = cleanups(predecessor).exists(ancestors(node).contains)
        if (entersCatch) {
          val kinds   = modes(predecessor)
          val resumed =
            Option.when(kinds.contains("throw.after"))(Position(predecessor, Some(PendingExit.Thrown))).toVector
          val failed = Option.when(kinds.contains("throw.before") || !kinds.contains("throw.after"))(
            Position(predecessor, pendingExit, exceptional = true)
          )
          resumed ++ failed
        } else if (!entersCleanup) Vector(Position(predecessor, pendingExit))
        else
          predecessor match {
            case _: Return =>
              Option.when(!pendingExit.contains(PendingExit.Thrown))(Position(predecessor, None)).toVector
            case control: ControlStructure if control.controlStructureType == ControlStructureTypes.THROW =>
              Option
                .when(!pendingExit.contains(PendingExit.Normal))(Position(predecessor, None, exceptional = true))
                .toVector
            case call: Call
                if inCleanup(call) && modes(call).exists(kind => kind == "normal.after" || kind == "throw.after") =>
              val selected = pendingExit.toList match {
                case Nil   => List(PendingExit.Normal, PendingExit.Thrown)
                case modes => modes
              }
              selected.flatMap { mode =>
                val label = if (mode == PendingExit.Normal) "normal.after" else "throw.after"
                Option.when(modes(call).contains(label))(Position(call, Some(mode)))
              }.toVector ++ Option.when(
                !pendingExit.contains(PendingExit.Normal) && modes(call).contains("throw.before")
              )(Position(call, None, exceptional = true))
            case call: Call =>
              Option
                .when(!pendingExit.contains(PendingExit.Thrown) && modes(call).contains("normal.after"))(
                  Position(call, None)
                )
                .toVector ++
                Option.when(!pendingExit.contains(PendingExit.Normal) && modes(call).contains("throw.before"))(
                  Position(call, None, exceptional = true)
                )
            case _ =>
              Option
                .when(!pendingExit.contains(PendingExit.Thrown) && modes(predecessor).contains("normal.after"))(
                  Position(predecessor, None)
                )
                .toVector
          }
      }
      .toVector
  }
}
