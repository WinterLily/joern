package io.joern.dataflowengineoss.passes.reachingdef

import io.shiftleft.codepropertygraph.generated.{Cpg, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.accesspath.{TrackedBase, TrackedNamedVariable}
import io.shiftleft.semanticcpg.accesspath.ConstantAccess
import io.joern.dataflowengineoss.queryengine.AccessPathUsage
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

object ReferenceAliases {
  def forNode(node: CfgNode): ReferenceAliases = {
    val method = node match {
      case method: Method => Some(method)
      case _              => Iterator.single(node).inAstMinusLeaf.isMethod.headOption
    }
    new ReferenceAliases(method)
  }
}

/** Stable direct reference copies, excluding languages where assignment may copy the object's fields. */
class ReferenceAliases(method: Option[Method]) {
  private val enabled     = method.exists(m => new Cpg(m.graph).metaData.language.contains("DART"))
  private val assignments =
    if (enabled) method.toList.flatMap(_.call).filter(_.name.startsWith("<operator>.assignment")) else Nil
  private val writes = assignments
    .flatMap { call =>
      call.argumentOption(1).collect { case identifier: Identifier => identifier.name -> call }
    }
    .groupMap(_._1)(_._2)
  private val parameters = method.toList.flatMap(_.parameter).map(_.name).toSet
  private val ambiguous  =
    if (enabled)
      method.toList
        .flatMap(_.ast.isIdentifier)
        .groupBy(_.name)
        .filter { case (_, identifiers) =>
          val declarations = identifiers.flatMap(_.refsTo).distinct
          declarations.size > 1 || declarations.exists {
            case local: Local                 => local._refIn.exists(_.isInstanceOf[ClosureBinding])
            case parameter: MethodParameterIn => parameter._refIn.exists(_.isInstanceOf[ClosureBinding])
            case _                            => false
          }
        }
        .keySet
    else Set.empty[String]
  private val dominance        = mutable.Map.empty[(Call, CfgNode), Boolean]
  private val killed           = mutable.Map.empty[(CfgNode, CfgNode, String, List[String]), Boolean]
  private lazy val fieldWrites = assignments.flatMap { assignment =>
    assignment.argumentOption(1).flatMap { target =>
      val (root, path) = AccessPathUsage.toTrackedBaseAndAccessPathSimple(target)
      val elements     = path.elements.elements.toList
      base(root, target) match {
        case TrackedNamedVariable(name) if elements.forall(_.isInstanceOf[ConstantAccess]) =>
          val fields       = elements.collect { case ConstantAccess(field) => field }
          val bindingWrite = target match {
            case identifier: Identifier => identifier.name == name
            case _                      => false
          }
          Option.when(fields.nonEmpty || bindingWrite)((name, fields, assignment))
        case _ => None
      }
    }
  }
  private lazy val reachable = {
    val seen    = mutable.HashSet.empty[CfgNode]
    val pending = mutable.ArrayDeque.from[CfgNode](method)
    while (pending.nonEmpty) {
      val node = pending.removeHead()
      if (seen.add(node)) pending ++= node._cfgOut.cast[CfgNode]
    }
    seen.toSet
  }

  private def dominates(assignment: Call, node: CfgNode): Boolean = dominance.getOrElseUpdate(
    (assignment, node), {
      if (!reachable.contains(node)) false
      else {
        val seen    = mutable.HashSet.empty[CfgNode]
        val pending = mutable.ArrayDeque.from[CfgNode](method)
        var bypass  = false
        while (pending.nonEmpty && !bypass) {
          val next = pending.removeHead()
          if (next != assignment && seen.add(next)) {
            if (next == node) bypass = true
            else pending ++= next._cfgOut.cast[CfgNode]
          }
        }
        !bypass
      }
    }
  )

  private def stable(name: String): Boolean =
    !ambiguous.contains(name) && writes.getOrElse(name, Nil).size + (if (parameters.contains(name)) 1 else 0) <= 1

  private def canonical(name: String, at: CfgNode, seen: Set[String] = Set.empty): String = {
    if (!stable(name) || seen.contains(name)) name
    else
      writes.getOrElse(name, Nil) match {
        case List(assignment) if assignment.name == Operators.assignment =>
          assignment.argumentOption(2) match {
            case Some(source: Identifier)
                if stable(source.name) && (at == assignment.argument(1) || dominates(assignment, at)) &&
                  writes.getOrElse(source.name, Nil).forall(dominates(_, assignment)) =>
              canonical(source.name, source, seen + name)
            case _ => name
          }
        case _ => name
      }
  }

  def base(value: TrackedBase, at: CfgNode): TrackedBase = value match {
    case TrackedNamedVariable(name) if enabled => TrackedNamedVariable(canonical(name, at))
    case _                                     => value
  }

  def sameReference(left: CfgNode, right: CfgNode): Boolean = {
    def name(node: CfgNode): Option[String] = node match {
      case identifier: Identifier        => Some(identifier.name)
      case parameter: MethodParameterIn  => Some(parameter.name)
      case parameter: MethodParameterOut => Some(parameter.name)
      case _                             => None
    }
    enabled && (for (a <- name(left); b <- name(right)) yield canonical(a, left) == canonical(b, right)).contains(true)
  }

  def overwritten(definition: CfgNode, use: CfgNode, root: TrackedBase, fields: List[String]): Boolean = root match {
    case TrackedNamedVariable(name) if enabled && fields.nonEmpty && reachable.contains(use) =>
      killed.getOrElseUpdate(
        (definition, use, name, fields), {
          val blockers = fieldWrites.collect {
            case (`name`, written, assignment) if fields.startsWith(written) => assignment
          }.toSet
          val origin = definition match {
            case _: MethodParameterIn => method.get
            // An assignment's output becomes available after its store, not at the evaluation of its left operand.
            case expression: Expression if expression.argumentIndex == 1 =>
              expression.inCall.find(_.name.startsWith("<operator>.assignment")).getOrElse(definition)
            case _ => definition
          }
          def reaches(blocked: Set[Call]): Boolean = {
            val seen    = mutable.HashSet.empty[CfgNode]
            val pending = mutable.ArrayDeque.from(origin._cfgOut.cast[CfgNode])
            var found   = false
            while (pending.nonEmpty && !found) {
              val node = pending.removeHead()
              if (node == use) found = true
              else if (!blocked.contains(node) && seen.add(node)) pending ++= node._cfgOut.cast[CfgNode]
            }
            found
          }
          blockers.nonEmpty && origin != use && reaches(Set.empty) && !reaches(blockers)
        }
      )
    case _ => false
  }
}
