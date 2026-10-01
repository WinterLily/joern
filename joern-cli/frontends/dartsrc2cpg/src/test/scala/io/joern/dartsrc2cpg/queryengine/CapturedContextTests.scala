package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.*
import io.joern.x2cpg.passes.base.ContainsEdgePass
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CapturedContextTests extends AnyWordSpec with Matchers {
  "Captured parameter contexts" should {
    "unwind only captured Dart frames and preserve the selected caller and field demand" in {
      for (
        language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN"); captured <- Seq(false, true);
        matchingOwner <- Seq(false, true); nested <- Seq(false, true); matched <- Seq(false, true)
      ) {
        val cpg = Cpg.empty
        try {
          val graph = cpg.graph
          graph.addNode(NewMetaData().language(language))
          val owner     = graph.addNode(NewMethod().name("owner").fullName("owner"))
          val other     = graph.addNode(NewMethod().name("other").fullName("other"))
          val parameter = graph.addNode(NewMethodParameterIn().name("input").index(1))
          val saved     = graph.addNode(NewLocal().name("saved"))
          val edges     = Cpg.newDiffGraphBuilder
          edges.addEdge(owner, parameter, EdgeTypes.AST)
          edges.addEdge(if (matchingOwner) owner else other, saved, EdgeTypes.AST)
          def invocation(callee: Method, name: String, scope: Method): (Call, Identifier) = {
            val call     = graph.addNode(NewCall().name(callee.name).methodFullName(callee.fullName))
            val argument = graph.addNode(NewIdentifier().name(name).code(name).argumentIndex(1))
            edges.addEdge(scope, call, EdgeTypes.AST)
            edges.addEdge(call, callee, EdgeTypes.CALL)
            edges.addEdge(call, argument, EdgeTypes.AST)
            edges.addEdge(call, argument, EdgeTypes.ARGUMENT)
            (call, argument)
          }
          def closure(name: String, source: Local, scope: Method): (Method, Local, Call) = {
            val method    = graph.addNode(NewMethod().name(name).fullName(name))
            val reference = graph.addNode(NewMethodRef().methodFullName(name))
            val binding   = graph.addNode(NewClosureBinding().closureBindingId(name + ":saved"))
            val proxy     = graph.addNode(NewLocal().name("saved").closureBindingId(name + ":saved"))
            edges.addEdge(method, proxy, EdgeTypes.AST)
            edges.addEdge(scope, reference, EdgeTypes.AST)
            edges.addEdge(reference, method, EdgeTypes.REF)
            if (captured) edges.addEdge(reference, binding, EdgeTypes.CAPTURE)
            edges.addEdge(binding, source, EdgeTypes.REF)
            (method, proxy, invocation(method, "closureArgument", scope)._1)
          }
          val (selected, argument)      = invocation(owner, "selected", other)
          val (_, unrelated)            = invocation(owner, "unrelated", other)
          val (outer, proxy, outerCall) = closure("outer", saved, owner)
          val frames = if (nested) List(closure("inner", proxy, outer)._3, outerCall) else List(outerCall)
          val stack  = (if (matched) Nil else frames) :+ selected
          edges.apply(graph)
          new ContainsEdgePass(cpg).createAndApply()
          val result = ReachableByResult(
            List(TaskFingerprint(parameter, stack, stack.size, fieldDemand = List("state"))),
            Vector(PathElement(parameter, stack, fieldDemand = List("state")))
          )
          val tasks    = new TaskCreator(EngineContext()).createFromResults(Vector(result))
          val expected = matched || language == "DART" && captured && matchingOwner
          withClue(s"$language captured=$captured owner=$matchingOwner nested=$nested matched=$matched") {
            tasks.map(_.sink).toSet shouldBe (if (expected) Set(argument) else Set.empty)
            tasks.foreach { task =>
              task.sink should not be unrelated
              task.callSiteStack shouldBe Nil
              task.callDepth shouldBe 0
              task.initialPath.head.callSiteStack shouldBe List(selected)
              task.taskStack.last.fieldDemand shouldBe List("state")
            }
          }
        } finally cpg.close()
      }
    }
  }
}
