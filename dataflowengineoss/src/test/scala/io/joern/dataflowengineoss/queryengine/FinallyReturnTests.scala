package io.joern.dataflowengineoss.queryengine

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
    "exclude pending values when finally returns or throws instead" in {
      for (cleanupKind <- Seq("normal", "return", "throw")) {
        val cpg = Cpg.empty
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
          withClue(cleanupKind) {
            pending.out(EdgeTypes.REACHING_DEF).contains(exit) shouldBe (cleanupKind == "normal")
          }
        } finally cpg.close()
      }
    }
  }
}
