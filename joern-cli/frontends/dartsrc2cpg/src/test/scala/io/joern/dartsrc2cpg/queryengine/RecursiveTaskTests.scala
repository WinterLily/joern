package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RecursiveTaskTests extends AnyWordSpec with Matchers {
  "Recursive caller tasks" should {
    "finish a Dart caller cycle while retaining connected sources and foreign depth limits" in {
      for (language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN")) {
        val cpg = Cpg.empty
        try {
          val graph = cpg.graph
          graph.addNode(NewMetaData().language(language))
          val left       = graph.addNode(NewMethod().name("left").fullName("left").isExternal(false))
          val right      = graph.addNode(NewMethod().name("right").fullName("right").isExternal(false))
          val leftInput  = graph.addNode(NewMethodParameterIn().name("value").code("value").index(1))
          val rightInput = graph.addNode(NewMethodParameterIn().name("value").code("value").index(1))
          val unrelated  = graph.addNode(NewMethodParameterIn().name("other").code("other").index(2))
          val edges      = Cpg.newDiffGraphBuilder
          edges.addEdge(left, leftInput, EdgeTypes.AST)
          edges.addEdge(left, unrelated, EdgeTypes.AST)
          edges.addEdge(right, rightInput, EdgeTypes.AST)
          def invocation(owner: Method, target: Method, input: MethodParameterIn): Unit = {
            val call     = graph.addNode(NewCall().name(target.name).methodFullName(target.fullName))
            val argument = graph.addNode(NewIdentifier().name("value").code("value").argumentIndex(1))
            edges.addEdge(owner, call, EdgeTypes.AST)
            edges.addEdge(call, target, EdgeTypes.CALL)
            edges.addEdge(call, argument, EdgeTypes.AST)
            edges.addEdge(call, argument, EdgeTypes.ARGUMENT)
            edges.addEdge(owner, argument, EdgeTypes.CONTAINS)
            edges.addEdge(input, argument, EdgeTypes.REACHING_DEF, "value")
          }
          invocation(left, right, leftInput)
          invocation(right, left, rightInput)
          edges.apply(graph)
          for (depth <- Seq(4, 8); shared <- Seq(false, true)) {
            def query(source: CfgNode): (List[TableEntry], Set[String]) = {
              val diagnostics = new QueryDiagnostics
              val engine      = new Engine(
                EngineContext(config =
                  EngineConfig(maxCallDepth = depth, shareCacheBetweenTasks = shared, diagnostics = Some(diagnostics))
                )
              )
              try (engine.backwards(List(rightInput), List(source)), diagnostics.limitations)
              finally engine.shutdown()
            }
            query(leftInput)._1 should not be empty
            val negative = query(unrelated)
            negative._1 shouldBe empty
            negative._2 shouldBe (if (language == "DART") Set.empty else Set("call-depth"))
          }
        } finally cpg.close()
      }
    }

    "prune only equivalent states previously searched with at least as much depth" in {
      val cpg = Cpg.empty
      try {
        val graph = cpg.graph
        graph.addNode(NewMetaData().language("DART"))
        val owner     = graph.addNode(NewMethod().name("owner").fullName("owner").isExternal(false))
        val parameter = graph.addNode(NewMethodParameterIn().name("input").index(1))
        val call      = graph.addNode(NewCall().name("owner").methodFullName("owner"))
        val other     = graph.addNode(NewCall().name("other"))
        val argument  = graph.addNode(NewIdentifier().name("input").argumentIndex(1))
        val edges     = Cpg.newDiffGraphBuilder
        edges.addEdge(owner, parameter, EdgeTypes.AST)
        edges.addEdge(call, owner, EdgeTypes.CALL)
        edges.addEdge(call, argument, EdgeTypes.ARGUMENT)
        edges.apply(graph)
        val generated                                                 = TaskFingerprint(argument, Nil, 2)
        def expand(previous: Option[TaskFingerprint], limit: Int = 4) = {
          val diagnostics = new QueryDiagnostics
          val result      =
            ReachableByResult(previous.toList :+ TaskFingerprint(parameter, Nil, 1), Vector(PathElement(parameter)))
          val tasks =
            new TaskCreator(EngineContext(config = EngineConfig(maxCallDepth = limit, diagnostics = Some(diagnostics))))
              .createFromResults(Vector(result))
          (tasks, diagnostics.limitations)
        }
        for (depth <- Seq(0, 1, 2)) {
          expand(Some(generated.copy(callDepth = depth)))._1 shouldBe empty
          expand(Some(generated.copy(callDepth = depth)), 1)._2 shouldBe empty
        }
        for (
          previous <- Seq(
            generated.copy(callDepth = 3),
            generated.copy(callSiteStack = List(other)),
            generated.copy(fieldDemand = List("other")),
            generated.copy(outputChannel = OutputChannel.ExceptionValue),
            generated.copy(storageDemands = List(StaticStorageDemand(owner, Nil, PendingExit.Thrown)))
          )
        ) {
          expand(Some(previous))._1.map(_.fingerprint) shouldBe Vector(generated)
        }
        expand(None, 1)._1 shouldBe empty
        expand(None, 1)._2 shouldBe Set("call-depth")
      } finally cpg.close()
    }
  }
}
