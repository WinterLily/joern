package io.joern.dataflowengineoss.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{
  ControlStructureTypes,
  Cpg,
  DispatchTypes,
  EdgeTypes,
  EvaluationStrategies
}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CaughtExceptionTests extends AnyWordSpec with Matchers {
  "Caught value dependencies" should {
    "bind explicit throw operands to their handler without mixing payloads and stacks" in {
      val cpg = Cpg.empty
      try {
        implicit val semantics: Semantics = DefaultSemantics()
        val graph                         = cpg.graph
        val method                        = graph.addNode(NewMethod().name("test").fullName("test").isExternal(false))
        val body                          = graph.addNode(NewBlock().order(3))
        val exit                          = graph.addNode(NewMethodReturn().typeFullName("void").code("RET").order(4))
        graph.applyDiff { diff =>
          diff.addEdge(method, body, EdgeTypes.AST)
          diff.addEdge(method, exit, EdgeTypes.AST)
        }
        val cases = (1 to 2).map { index =>
          val statement =
            graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(index))
          val protectedBody = graph.addNode(NewBlock().order(1))
          val thrown        = graph.addNode(
            NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(s"throw $index").order(1)
          )
          val parameter = graph.addNode(
            NewMethodParameterIn()
              .name(s"value$index")
              .code(s"Object value$index")
              .typeFullName("Object")
              .index(index)
              .order(index)
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          )
          val value = graph.addNode(
            NewIdentifier().name(s"value$index").code(s"value$index").typeFullName("Object").order(1).argumentIndex(1)
          )
          val stack =
            graph.addNode(NewLiteral().code(s"stack$index").typeFullName("StackTrace").order(2).argumentIndex(2))
          val handler = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.CATCH).order(2))
          val condition = graph.addNode(NewLiteral().code("true").typeFullName("bool").order(1))
          def channel(name: String, order: Int): Call = graph.addNode(
            NewCall()
              .name(name)
              .methodFullName(name)
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
              .code(s"$name@$index")
              .typeFullName("Object")
              .order(order)
          )
          val exception = channel("<operator>.caughtException", 2)
          val trace     = channel("<operator>.caughtStackTrace", 3)
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                body          -> statement,
                statement     -> protectedBody,
                statement     -> handler,
                protectedBody -> thrown,
                thrown        -> value,
                thrown        -> stack,
                handler       -> condition,
                handler       -> exception,
                handler       -> trace
              )
            )
              diff.addEdge(parent, child, EdgeTypes.AST)
            diff.addEdge(method, parameter, EdgeTypes.AST)
            diff.addEdge(value, parameter, EdgeTypes.REF)
            diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
            diff.addEdge(statement, handler, EdgeTypes.CATCH_BODY)
            diff.addEdge(handler, condition, EdgeTypes.CONDITION)
            diff.addEdge(thrown, stack, EdgeTypes.ARGUMENT)
            diff.addEdge(thrown, value, EdgeTypes.ARGUMENT)
          }
          (value, stack, exception, trace)
        }
        new CfgCreationPass(cpg).createAndApply()
        for ((value, stack, _, _) <- cases) {
          value._cfgOut.toSet should contain(stack)
          stack.cfgNext.isControlStructure.isThrow.size shouldBe 1
        }
        new ReachingDefPass(cpg).createAndApply()
        for ((value, stack, exception, trace) <- cases) {
          value._reachingDefIn.toSet should contain(value.refsTo.head)
          exception._reachingDefIn.toSet should contain(value)
          trace._reachingDefIn.toSet should contain(stack)
          exception._reachingDefIn.toSet should not contain stack
          trace._reachingDefIn.toSet should not contain value
          for ((otherValue, otherStack, _, _) <- cases if otherValue != value) {
            exception._reachingDefIn.toSet should not contain otherValue
            trace._reachingDefIn.toSet should not contain otherStack
          }
        }
      } finally cpg.close()
    }
  }
}
