package io.joern.dataflowengineoss.passes.reachingdef

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.Engine.{isOutputArgOfInternalMethod, semanticsForCall}
import io.joern.dataflowengineoss.semanticsloader.{FlowPath, FlowSemantic, ParameterNode, Semantics}
import io.joern.dataflowengineoss.semanticsloader.FlowPath.{FlowMapping, PassThroughMapping}
import io.shiftleft.codepropertygraph.generated.nodes.{Call, CfgNode, Expression, StoredNode}
import io.shiftleft.semanticcpg.language.*

object EdgeValidator {

  /** Determines whether the edge from `parentNode`to `childNode` is valid, according to the given semantics.
    */
  def isValidEdge(childNode: CfgNode, parentNode: CfgNode)(implicit semantics: Semantics): Boolean =
    (childNode, parentNode) match {
      case (childNode: Expression, parentNode)
          if isCallRetval(parentNode) || !isValidEdgeToExpression(parentNode, childNode) =>
        false
      case (call: Call, argument: Expression)
          if io.joern.dataflowengineoss.isDart(call) && call.argument.contains(argument) =>
        val summaries = semanticsForCall(call)
        summaries.isEmpty || summaries.exists(_.mappings.exists {
          case FlowMapping(ParameterNode(_, Some(name)), ParameterNode(-1, None)) if argument.argumentName.isDefined =>
            argument.argumentName.contains(name)
          case FlowMapping(ParameterNode(index, _), ParameterNode(-1, None)) => index == argument.argumentIndex
          case PassThroughMapping                                            => argument.argumentIndex != 0
          case _                                                             => false
        })
      case (childNode: Expression, parentNode: Expression)
          if parentNode.isArgToSameCallWith(childNode) && childNode.isDefined && parentNode.isUsed =>
        parentNode.hasDefinedFlowTo(childNode)
      case (_: Expression, _: Expression)                  => true
      case (childNode: Expression, _) if !childNode.isUsed => false
      case (_: Expression, _)                              => true
      case (_, parentNode)                                 => !isCallRetval(parentNode)
    }

  private def isValidEdgeToExpression(parNode: CfgNode, curNode: Expression)(implicit semantics: Semantics): Boolean =
    parNode match {
      case parentNode: Expression =>
        val sameCallSite = parentNode.inCall.l == curNode.start.inCall.l
        !(sameCallSite && isOutputArgOfInternalMethod(parentNode)) &&
        (sameCallSite && parentNode.isUsed && curNode.isDefined || !sameCallSite && curNode.isUsed)
      case _ =>
        curNode.isUsed
    }

  /** Is it a CALL for which semantics exist but don't taint its return value?
    */
  private def isCallRetval(parentNode: StoredNode)(implicit semantics: Semantics): Boolean =
    parentNode match {
      case call: Call => semanticsForCall(call).exists(!explicitlyFlowsToReturnValue(_))
      case _          => false
    }

  private def explicitlyFlowsToReturnValue(flowSemantic: FlowSemantic): Boolean =
    flowSemantic.mappings.exists(explicitlyFlowsToReturnValue)

  private def explicitlyFlowsToReturnValue(flowPath: FlowPath): Boolean = flowPath match {
    // Some frontends (e.g. python) denote named arguments using `-1` as the argument index. As such
    // `-1` denotes the return value only if there's no argument name.
    case FlowMapping(_, ParameterNode(-1, None)) => true
    case PassThroughMapping                      => true
    case _                                       => false
  }
}
