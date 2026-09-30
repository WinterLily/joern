package io.joern.dataflowengineoss.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.passes.reachingdef.EdgeValidator
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, FullNameSemantics, ParameterNode, Semantics}
import io.joern.dataflowengineoss.semanticsloader.FlowPath.FlowMapping
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ReturnSummaryTests extends AnyWordSpec with Matchers {
  "Return summaries" should {
    "filter each return dependency by the source parameter including named arguments" in {
      val cpg = Cpg.empty
      try {
        val graph    = cpg.graph
        val method   = graph.addNode(NewMethod().name("choose").fullName("choose").isExternal(true))
        val call     = graph.addNode(NewCall().name("choose").methodFullName("choose"))
        val selected = graph.addNode(NewIdentifier().name("selected").argumentIndex(1).argumentName("value"))
        val ignored  = graph.addNode(NewIdentifier().name("ignored").argumentIndex(2).argumentName("predicate"))
        val diff     = Cpg.newDiffGraphBuilder
        val caller   = graph.addNode(NewMethod().name("caller").fullName("caller").isExternal(false))
        diff.addEdge(caller, call, EdgeTypes.AST)
        diff.addEdge(call, method, EdgeTypes.CALL)
        for (argument <- Seq(selected, ignored)) {
          diff.addEdge(call, argument, EdgeTypes.AST)
          diff.addEdge(call, argument, EdgeTypes.ARGUMENT)
        }
        diff.apply(graph)
        for (source <- Seq(ParameterNode(1), ParameterNode(-1, Some("value")))) {
          implicit val semantics: Semantics = FullNameSemantics.fromList(
            List(
              FlowSemantic(
                "choose",
                List(FlowMapping(source, ParameterNode(-1)), FlowMapping(ParameterNode(2), ParameterNode(2)))
              )
            )
          )
          semantics.initialize(cpg)
          EdgeValidator.isValidEdge(call, selected) shouldBe true
          EdgeValidator.isValidEdge(call, ignored) shouldBe false
        }
      } finally cpg.close()
    }
  }
}
