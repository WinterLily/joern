package io.joern.dataflowengineoss.queryengine

import flatgraph.misc.TestUtils.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class QueryDiagnosticsTests extends AnyWordSpec with Matchers {
  "Nested output arguments" should {
    "preserve the enclosing call stack when expanding a callee mutation" in {
      val cpg = Cpg.empty
      try {
        val graph     = cpg.graph
        val method    = graph.addNode(NewMethod().name("mutate").fullName("mutate").isExternal(false))
        val parameter = graph.addNode(NewMethodParameterIn().name("object").index(1))
        val output    = graph.addNode(NewMethodParameterOut().name("object").index(1))
        val call      = graph.addNode(NewCall().name("mutate").methodFullName("mutate"))
        val argument  = graph.addNode(NewIdentifier().name("object").argumentIndex(1))
        val outer     = graph.addNode(NewCall().name("wrapper").methodFullName("wrapper"))
        val edges     = Cpg.newDiffGraphBuilder
        edges.addEdge(method, parameter, EdgeTypes.AST)
        edges.addEdge(method, output, EdgeTypes.AST)
        edges.addEdge(parameter, output, EdgeTypes.PARAMETER_LINK)
        edges.addEdge(call, method, EdgeTypes.CALL)
        edges.addEdge(call, argument, EdgeTypes.ARGUMENT)
        edges.apply(graph)
        val result = ReachableByResult(
          List(TaskFingerprint(argument, List(outer), 1)),
          Vector(PathElement(argument, List(outer), isOutputArg = true))
        )
        val tasks = new TaskCreator(EngineContext()).createFromResults(Vector(result))
        tasks.map(_.sink) shouldBe Vector(output)
        tasks.head.callSiteStack shouldBe List(call, outer)
        tasks.head.callDepth shouldBe 2
        val semantics = io.joern.dataflowengineoss
          .DefaultSemantics()
          .plus(List(io.joern.dataflowengineoss.semanticsloader.FlowSemantic.from("mutate", List((1, 1), (1, -1)))))
        semantics.initialize(cpg)
        new TaskCreator(EngineContext(semantics = semantics)).createFromResults(Vector(result)) shouldBe empty
      } finally cpg.close()
    }
  }

  "Query diagnostics" should {
    "record discarded work without changing task expansion" in {
      val cpg = Cpg.empty
      try {
        val graph     = cpg.graph
        val method    = graph.addNode(NewMethod().name("f").fullName("f").isExternal(false))
        val parameter = graph.addNode(NewMethodParameterIn().name("x").index(1))
        val call      = graph.addNode(NewCall().name("f").methodFullName("f"))
        val argument  = graph.addNode(NewIdentifier().name("input").argumentIndex(1))
        val diff      = Cpg.newDiffGraphBuilder
        diff.addEdge(method, parameter, EdgeTypes.AST)
        diff.addEdge(call, method, EdgeTypes.CALL)
        diff.addEdge(call, argument, EdgeTypes.ARGUMENT)
        diff.apply(graph)
        val result = ReachableByResult(List(TaskFingerprint(parameter, Nil, 0)), Vector(PathElement(parameter)))
        def expand(config: EngineConfig) =
          new TaskCreator(EngineContext(config = config)).createFromResults(Vector(result))
        val depth = new QueryDiagnostics
        expand(EngineConfig(maxCallDepth = 0, diagnostics = Some(depth))) shouldBe empty
        depth.limitations shouldBe Set("call-depth")
        val arguments = new QueryDiagnostics
        expand(EngineConfig(maxArgsToAllow = 0, diagnostics = Some(arguments))) shouldBe empty
        arguments.limitations shouldBe Set("parameter-arguments")
        val methodReturn  = graph.addNode(NewMethodReturn())
        val returned      = graph.addNode(NewReturn().code("return x"))
        val reference     = graph.addNode(NewMethodRef().methodFullName("f"))
        val callbackEdges = Cpg.newDiffGraphBuilder
        callbackEdges.addEdge(method, methodReturn, EdgeTypes.AST)
        callbackEdges.addEdge(reference, method, EdgeTypes.REF)
        callbackEdges.addEdge(returned, methodReturn, EdgeTypes.REACHING_DEF, "x")
        callbackEdges.apply(graph)
        val callbackResult = ReachableByResult(
          List(TaskFingerprint(reference, Nil, 0)),
          Vector(PathElement(reference, isOutputArg = true))
        )
        val outputs = new QueryDiagnostics
        new TaskCreator(EngineContext(config = EngineConfig(maxOutputArgsExpansion = 0, diagnostics = Some(outputs))))
          .createFromResults(Vector(callbackResult)) shouldBe empty
        outputs.limitations shouldBe Set("output-argument-expansion")
        val complete = new QueryDiagnostics
        expand(EngineConfig(diagnostics = Some(complete))) shouldBe expand(EngineConfig())
        complete.limitations shouldBe empty
        depth.limitations shouldBe Set("call-depth")
      } finally cpg.close()
    }
  }
}
