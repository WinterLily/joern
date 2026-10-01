package io.joern.dartsrc2cpg.queryengine

import io.joern.dataflowengineoss.queryengine.*

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.joern.x2cpg.{Ast, ValidationMode}
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
    "preserve pending values only across normally completing cleanup" in {
      for (
        origin   <- Seq("throw", "return");
        scenario <- Seq(
          "normal",
          "handled",
          "replacement",
          "return",
          "break",
          "continue",
          "local break",
          "local continue"
        )
      ) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language("DART"))
        try {
          implicit val semantics: Semantics       = DefaultSemantics()
          implicit val validation: ValidationMode = ValidationMode.Enabled
          def literal(code: String): Ast          = Ast(NewLiteral().code(code).typeFullName("Object"))
          def block(children: Ast*): Ast          = Ast(NewBlock()).withChildren(children)
          def throwing(code: String): Ast         = {
            val thrown = NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(s"throw $code")
            val value  = NewLiteral().code(code).typeFullName("Object").argumentIndex(1)
            Ast(thrown).withChild(Ast(value)).withArgEdge(thrown, value)
          }
          def handler(name: String): Ast = {
            val caught    = NewControlStructure().controlStructureType(ControlStructureTypes.CATCH)
            val condition = NewLiteral().code("true").typeFullName("bool")
            val value     = NewCall()
              .name("<operator>.caughtException")
              .methodFullName("<operator>.caughtException")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
              .typeFullName("Object")
              .code(name)
            Ast(caught).withChild(Ast(condition)).withConditionEdge(caught, condition).withChild(Ast(value))
          }
          def protecting(body: Ast, caught: Option[Ast] = None, cleanup: Option[Ast] = None): Ast = {
            val statement = NewControlStructure().controlStructureType(ControlStructureTypes.TRY)
            var ast       = Ast(statement).withChild(body).withTryBodyEdge(statement, body.root.get)
            caught.foreach(value => ast = ast.withChild(value).withCatchBodyEdge(statement, value.root.get))
            cleanup.foreach(value => ast = ast.withChild(value).withFinallyBodyEdge(statement, value.root.get))
            ast
          }
          def loop(body: Ast): Ast = {
            val statement = NewControlStructure().controlStructureType(ControlStructureTypes.DO)
            val condition = NewLiteral().code("false").typeFullName("bool")
            Ast(statement)
              .withChild(body)
              .withDoBodyEdge(statement, body.root.get)
              .withChild(Ast(condition))
              .withConditionEdge(statement, condition)
          }
          def jump(kind: String): Ast = Ast(NewControlStructure().controlStructureType(kind).code(kind))
          val cleanup                 = scenario match {
            case "handled"        => protecting(block(throwing("cleanup")), Some(handler("inner caught")))
            case "replacement"    => throwing("cleanup")
            case "return"         => Ast(NewReturn().code("return constant"))
            case "break"          => jump(ControlStructureTypes.BREAK)
            case "continue"       => jump(ControlStructureTypes.CONTINUE)
            case "local break"    => loop(block(jump(ControlStructureTypes.BREAK)))
            case "local continue" => loop(block(jump(ControlStructureTypes.CONTINUE)))
            case _                => literal("cleanup completed")
          }
          val pending =
            if (origin == "throw") throwing("original")
            else {
              val returned = NewReturn().code("return original")
              val value    = NewLiteral().code("original").typeFullName("Object").argumentIndex(1)
              Ast(returned).withChild(Ast(value)).withArgEdge(returned, value)
            }
          val protectedBody = protecting(block(pending), cleanup = Some(block(cleanup)))
          val body          = if (Set("break", "continue")(scenario)) {
            val after = Ast(
              NewCall()
                .name("after")
                .methodFullName("after")
                .code("after()")
                .dispatchType(DispatchTypes.STATIC_DISPATCH)
            )
            block(loop(block(protectedBody)), after)
          } else block(protectedBody)
          val statement = protecting(body, Some(handler("outer caught")))
          val method    = NewMethod().name("test").fullName("test").isExternal(false)
          val ast       =
            Ast(method).withChild(block(statement)).withChild(Ast(NewMethodReturn().code("RET").typeFullName("void")))
          val diff = Cpg.newDiffGraphBuilder
          Ast.storeInDiffGraph(ast, diff)
          flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diff)
          new CfgCreationPass(cpg).createAndApply()
          new ReachingDefPass(cpg).createAndApply()
          val outer = cpg.call.codeExact("outer caught").head._reachingDefIn.cast[CfgNode].code.toSet
          withClue(s"$origin: $scenario") {
            val preserved = Set("normal", "handled", "local break", "local continue")(scenario)
            if (origin == "throw") outer.contains("original") shouldBe preserved
            else
              cpg.ret
                .codeExact("return original")
                .head
                ._reachingDefOut
                .toSet
                .contains(cpg.method.nameExact("test").methodReturn.head) shouldBe preserved
            outer.contains("cleanup") shouldBe (scenario == "replacement")
            if (scenario == "handled") {
              val inner = cpg.call.codeExact("inner caught").head._reachingDefIn.cast[CfgNode].code.toSet
              inner should contain("cleanup")
              inner should not contain "original"
            }
          }
        } finally cpg.close()
      }
    }

    "bind explicit throw operands to their handler without mixing payloads and stacks" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
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
