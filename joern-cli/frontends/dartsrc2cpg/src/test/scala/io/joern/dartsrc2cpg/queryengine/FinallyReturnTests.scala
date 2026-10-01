package io.joern.dartsrc2cpg.queryengine

import io.joern.dataflowengineoss.queryengine.*

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, Cpg, DispatchTypes, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FinallyReturnTests extends AnyWordSpec with Matchers {
  "Return dependencies" should {
    "preserve a pending return only when cleanup handles its own exception" in {
      for (scenario <- Seq("inside", "outside", "nested cleanup", "rethrow", "replacement return")) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language("DART"))
        try {
          implicit val semantics: Semantics                     = DefaultSemantics()
          val graph                                             = cpg.graph
          def attach(parent: AstNode, children: AstNode*): Unit = graph.applyDiff { diff =>
            children.zipWithIndex.foreach { case (child, index) =>
              diff.setNodeProperty(child, "ORDER", index + 1)
              diff.addEdge(parent, child, EdgeTypes.AST)
            }
          }
          def block(children: AstNode*): Block = {
            val result = graph.addNode(NewBlock())
            attach(result, children*)
            result
          }
          def throwing(code: String): ControlStructure = {
            val result =
              graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(code))
            val value = graph.addNode(NewLiteral().code("error").typeFullName("Object").argumentIndex(1))
            attach(result, value)
            graph.applyDiff(_.addEdge(result, value, EdgeTypes.ARGUMENT))
            result
          }
          def handler(body: AstNode): ControlStructure = {
            val result    = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.CATCH))
            val condition = graph.addNode(NewLiteral().code("true").typeFullName("bool"))
            attach(result, condition, body)
            graph.applyDiff(_.addEdge(result, condition, EdgeTypes.CONDITION))
            result
          }
          def protecting(
            body: Block,
            catches: Option[ControlStructure] = None,
            cleanup: Option[Block] = None
          ): ControlStructure = {
            val result = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY))
            attach(result, (Seq(body) ++ catches.toSeq ++ cleanup.toSeq)*)
            graph.applyDiff { diff =>
              diff.addEdge(result, body, EdgeTypes.TRY_BODY)
              catches.foreach(diff.addEdge(result, _, EdgeTypes.CATCH_BODY))
              cleanup.foreach(diff.addEdge(result, _, EdgeTypes.FINALLY_BODY))
            }
            result
          }
          val method  = graph.addNode(NewMethod().name("test").fullName("test").isExternal(false))
          val exit    = graph.addNode(NewMethodReturn().typeFullName("String").code("RET"))
          val pending = graph.addNode(NewReturn().code("return input"))
          val input   = graph.addNode(NewLiteral().code("input").typeFullName("String").argumentIndex(1))
          attach(pending, input)
          graph.applyDiff(_.addEdge(pending, input, EdgeTypes.ARGUMENT))
          val caughtBody: AstNode = scenario match {
            case "rethrow"            => throwing("rethrow")
            case "replacement return" => graph.addNode(NewReturn().code("return replacement"))
            case _                    => graph.addNode(NewLiteral().code("handled").typeFullName("String"))
          }
          val caught    = handler(block(caughtBody))
          val failure   = throwing("throw error")
          val statement = scenario match {
            case "outside" =>
              protecting(block(protecting(block(pending), cleanup = Some(block(failure)))), catches = Some(caught))
            case "nested cleanup" =>
              val nested = protecting(
                block(graph.addNode(NewLiteral().code("work").typeFullName("String"))),
                cleanup = Some(block(failure))
              )
              protecting(block(pending), cleanup = Some(block(protecting(block(nested), catches = Some(caught)))))
            case _ =>
              protecting(block(pending), cleanup = Some(block(protecting(block(failure), catches = Some(caught)))))
          }
          attach(method, block(statement), exit)
          new CfgCreationPass(cpg).createAndApply()
          new ReachingDefPass(cpg).createAndApply()
          withClue(scenario) {
            pending.out(EdgeTypes.REACHING_DEF).contains(exit) shouldBe Set("inside", "nested cleanup").contains(
              scenario
            )
          }
        } finally cpg.close()
      }
    }

    "exclude pending values when finally returns or throws instead" in {
      for (
        language <- Seq("C", "JAVASCRIPT", "JAVA", "KOTLIN", "DART"); cleanupKind <- Seq("normal", "return", "throw")
      ) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language(language))
        try {
          implicit val semantics: Semantics = DefaultSemantics()
          val graph                         = cpg.graph
          val method                        = graph.addNode(NewMethod().name("test").fullName("test").isExternal(false))
          val exit      = graph.addNode(NewMethodReturn().typeFullName("String").code("RET").order(2))
          val body      = graph.addNode(NewBlock().order(1))
          val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val protectedBody = graph.addNode(NewBlock().order(1))
          val finalBody     = graph.addNode(NewBlock().order(3))
          val pending       = graph.addNode(NewReturn().code("return input").order(1))
          val input         = graph.addNode(NewLiteral().code("input").typeFullName("String").order(1).argumentIndex(1))
          val cleanup: CfgNode = cleanupKind match {
            case "return" => graph.addNode(NewReturn().code("return constant").order(1))
            case "throw"  =>
              graph.addNode(
                NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code("throw error").order(1)
              )
            case _ =>
              graph.addNode(
                NewCall()
                  .name("cleanup")
                  .methodFullName("cleanup")
                  .dispatchType(DispatchTypes.STATIC_DISPATCH)
                  .code("cleanup")
                  .order(1)
              )
          }
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                method        -> body,
                method        -> exit,
                body          -> statement,
                statement     -> protectedBody,
                statement     -> finalBody,
                protectedBody -> pending,
                pending       -> input,
                finalBody     -> cleanup
              )
            ) diff.addEdge(parent, child, EdgeTypes.AST)
            diff.addEdge(pending, input, EdgeTypes.ARGUMENT)
            diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
            diff.addEdge(statement, finalBody, EdgeTypes.FINALLY_BODY)
          }
          new CfgCreationPass(cpg).createAndApply()
          new ReachingDefPass(cpg).createAndApply()
          withClue(s"$language $cleanupKind") {
            pending.out(EdgeTypes.REACHING_DEF).contains(exit) shouldBe (language != "DART" || cleanupKind == "normal")
          }
        } finally cpg.close()
      }
    }
  }
}
