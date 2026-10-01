package io.joern

import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes.Assignment
import io.shiftleft.semanticcpg.utils.MemberAccess.isFieldAccess
import scala.collection.mutable

package object dataflowengineoss {

  def isDart(node: StoredNode): Boolean =
    new io.shiftleft.codepropertygraph.generated.Cpg(node.graph).metaData.language.contains("DART")

  /** Returns the target of an assignment involving [[lit]], if the assignment is found inside a module method.
    *
    * @param lit
    *   the literal to appear on the RHS of the assignment
    * @param recursive
    *   should [[lit]] be the sole RHS (false) or can it be an arbitrarily nested sub-expression (true)
    * @return
    *   the LHS of the assignment
    */
  @scala.annotation.nowarn("cat=deprecation")
  def globalFromLiteral(lit: Literal, recursive: Boolean = true): Iterator[Expression] = {

    /** Frontends often create three-address code representations of compound literals, e.g. in pysrc2cpg the dictionary
      * literal `{"x": y}` is lowered as a block `{tmp0 = {}; tmp0["x"] = y; tmp0}`.
      *
      * In an assignment `foo = {"x": "y"}`, if [[lit]] is "y", we don't want to pick the intermediate assignment
      * `tmp0["x"] = "y"`, since `tmp0` is never a global/module-level variable. Instead, we want to pick `foo`.
      */
    def skipLowLevelAssignments(assignments: Iterator[Assignment]): Iterator[Assignment] = {
      assignments.repeat(_.parentBlock.inCall.isAssignment)(_.emit).lastOption.fold(assignments)(_.iterator)
    }

    lit.start
      .where(_.method.isModule)
      .flatMap(t => if (recursive) t.inAssignment else skipLowLevelAssignments(t.inCall.isAssignment))
      .target
  }

  def identifierToFirstUsages(node: Identifier): List[Identifier] =
    node.refsTo.flatMap(firstIdentifierFromCapturedScopes(_)).l

  def firstIdentifierFromCapturedScopes(
    declaration: Declaration,
    includeModeledInputs: Boolean = false
  ): List[Identifier] = {
    if (!isDart(declaration))
      return declaration.capturedByMethodRef.referencedMethod
        .flatMap(_.ast.isIdentifier.nameExact(declaration.name).sortBy(x => (x.lineNumber, x.columnNumber)).headOption)
        .l
    val result  = mutable.LinkedHashSet.empty[Identifier]
    val visited = mutable.HashSet.empty[(ClosureBinding, MethodRef)]

    def captured(source: Declaration, eligible: MethodRef => Boolean): Unit = {
      source.closureBinding.foreach { binding =>
        binding._captureIn.collectAll[MethodRef].filter(eligible).foreach { reference =>
          if (visited.add((binding, reference))) reference.referencedMethod.foreach { method =>
            val proxies = method.local
              .filter(local => binding.closureBindingId.nonEmpty && local.closureBindingId == binding.closureBindingId)
              .toSet
            val declarations: Set[Declaration]          = proxies.toSet[Declaration] + source
            def refers(identifier: Identifier): Boolean = identifier.refsTo.exists(declarations.contains)
            // Some frontends also use captures for modeled callback inputs, without lexical proxy uses.
            if (
              includeModeledInputs && source
                .isInstanceOf[MethodParameterIn] && !method.ast.isIdentifier.exists(refers) &&
              proxies.forall(_.closureBinding.isEmpty)
            ) result ++= method.ast.isIdentifier
            def replaces(call: Call): Boolean = call.name == Operators.assignment &&
              call.argumentOption(1).exists {
                case identifier: Identifier => refers(identifier)
                case _                      => false
              }
            val seen    = mutable.HashSet.empty[CfgNode]
            val pending = mutable.ArrayDeque[CfgNode](method)
            while (pending.nonEmpty) {
              val node = pending.removeHead()
              if (seen.add(node)) {
                node match {
                  case identifier: Identifier
                      if refers(identifier) && !identifier.inCall
                        .exists(call => replaces(call) && identifier.argumentIndex == 1) =>
                    result += identifier
                  case _ =>
                }
                node match {
                  case call: Call if replaces(call) =>
                  case _                            => pending ++= node._cfgOut.cast[CfgNode]
                }
              }
            }
            proxies.foreach(proxy => captured(proxy, seen.contains))
          }
        }
      }
    }

    captured(declaration, _ => true)
    result.toList
  }

}
