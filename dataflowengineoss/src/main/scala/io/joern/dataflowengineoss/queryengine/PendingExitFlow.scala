package io.joern.dataflowengineoss.queryengine

import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

private[queryengine] class PendingExitFlow(config: EngineConfig) {
  private val routing     = new DartExitRouting
  private val cache       = mutable.Map.empty[(CfgNode, CfgNode, PendingExit), Vector[Option[PendingExit]]]
  private val normalCalls = mutable.Map.empty[Call, Boolean]

  // DDG assignment values live on LHS identifiers, which the CFG visits before evaluating the RHS.
  private def valuePoint(node: CfgNode): CfgNode = node._astIn
    .collectAll[Call]
    .find { call =>
      call.name == Operators.assignment && call.argument.exists(arg => arg.argumentIndex == 1 && arg == node)
    }
    .getOrElse(node)

  def expand(current: PathElement, parent: PathElement): Vector[PathElement] = {
    val use        = current.node.asInstanceOf[CfgNode]
    val definition = parent.node.asInstanceOf[CfgNode]
    current.storageDemands.find(d => d.method == use.method && d.callSiteStack == current.callSiteStack) match {
      case None         => Vector(parent)
      case Some(demand) =>
        val exits =
          if (!routing.complete(use.method)) {
            config.diagnostics.foreach(_.record("static-storage-exit-metadata"))
            Vector.empty
          } else if (definition.isInstanceOf[MethodParameterIn]) Vector(None)
          else if (definition.method != use.method) {
            config.diagnostics.foreach(_.record("static-storage-value-context"))
            Vector(Some(demand.pendingExit))
          } else
            cache.getOrElseUpdate(
              (valuePoint(use), valuePoint(definition), demand.pendingExit),
              predecessors(valuePoint(use), valuePoint(definition), demand.pendingExit)
            )
        exits.map { exit =>
          val retained = current.storageDemands.filterNot(_ == demand)
          parent.copy(storageDemands =
            (retained ++ exit.map(mode => demand.copy(pendingExit = mode))).sortBy(_.orderingKey)
          )
        }
    }
  }

  private def completes(call: Call): Boolean = normalCalls.getOrElseUpdate(
    call, {
      val targets = NoResolve.getCalledMethods(call).toVector
      if (targets.isEmpty || targets.exists(method => method.isExternal || method.start.isStub.nonEmpty)) {
        config.diagnostics.foreach(_.record("static-storage-value-external-effects"))
        true
      } else
        targets.exists { method =>
          if (!routing.complete(method)) {
            config.diagnostics.foreach(_.record("static-storage-exit-metadata"))
            false
          } else method.methodReturn._cfgIn.cast[CfgNode].exists(node => routing.modes(node).contains("normal.after"))
        }
    }
  )

  private def predecessors(use: CfgNode, definition: CfgNode, exit: PendingExit): Vector[Option[PendingExit]] = {
    val pending = mutable.ArrayDeque.from(routing.before(use, Some(exit)))
    val seen    = mutable.HashSet.empty[DartExitRouting.Position]
    val found   = mutable.HashSet.empty[Option[PendingExit]]
    while (pending.nonEmpty && seen.size < config.maxStaticStorageNodes) {
      val position = pending.removeHead()
      if (seen.add(position)) {
        position.node match {
          case call: Call if position.exceptional && call.name.startsWith("<operator>") =>
            config.diagnostics.foreach(_.record("static-storage-implicit-exception-effects"))
          case call: Call if !position.exceptional && !call.name.startsWith("<operator>") && !completes(call) =>
          case node if node == definition && !position.exceptional => found.add(position.pendingExit)
          case _ => pending.appendAll(routing.before(position.node, position.pendingExit))
        }
      }
    }
    if (pending.nonEmpty) config.diagnostics.foreach(_.record("static-storage-value-search"))
    found.toVector.sortBy(_.map(_.ordinal).getOrElse(-1))
  }
}
