package io.joern.dataflowengineoss.queryengine

import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.accesspath.{ConstantAccess, TrackedBase, TrackedUnknown}
import io.shiftleft.semanticcpg.language.*

private[queryengine] object FieldDemand {
  private val preserving = Set(
    Operators.assignment,
    Operators.cast,
    Operators.conditional,
    Operators.fieldAccess,
    "<operator>.caughtException",
    "<operator>.caughtStackTrace"
  )

  private def location(node: CfgNode): Option[(TrackedBase, List[String])] = node match {
    case _: Expression | _: MethodParameterIn | _: MethodParameterOut =>
      val (base, path) = AccessPathUsage.toTrackedBaseAndAccessPathSimple(node)
      val elements     = path.elements.elements.toList
      Option.when(base != TrackedUnknown && elements.forall(_.isInstanceOf[ConstantAccess]))(base -> elements.collect {
        case ConstantAccess(name) => name
      })
    case _ => None
  }

  def transfer(current: CfgNode, parent: CfgNode, demand: List[String]): Option[List[String]] = {
    (current, parent) match {
      case (target: Expression, source: Expression) =>
        val sharedCalls = target.inCall.toSet.intersect(source.inCall.toSet)
        if (
          sharedCalls.exists(_.name == Operators.assignment) && target.argumentIndex == 1 && source.argumentIndex == 2
        )
          return Some(demand)
        if (sharedCalls.exists(call => !preserving.contains(call.name))) return Some(Nil)
      case _ =>
    }
    current match {
      // A general transformation summary does not promise to preserve object layout.
      case call: Call if !preserving.contains(call.name) => return Some(Nil)
      case _                                             =>
    }
    (location(current), location(parent)) match {
      case (Some((currentBase, currentFields)), Some((parentBase, parentFields))) if currentBase == parentBase =>
        val requested = currentFields ++ demand
        if (requested.startsWith(parentFields)) Some(requested.drop(parentFields.size))
        else if (parentFields.startsWith(requested)) Some(Nil)
        else None
      case _ => Some(demand)
    }
  }
}
