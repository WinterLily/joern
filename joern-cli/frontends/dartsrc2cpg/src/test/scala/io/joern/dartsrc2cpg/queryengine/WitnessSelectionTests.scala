package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

class WitnessSelectionTests extends AnyWordSpec with Matchers {
  "Witness selection" should {
    "retain distinct Dart routes through task solving and report a bound" in {
      for (language <- Seq("DART", "C", "JAVASCRIPT", "JAVA", "KOTLIN")) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language(language))
        try {
          val graph    = cpg.graph
          val source   = graph.addNode(NewIdentifier().name("source"))
          val sink     = graph.addNode(NewIdentifier().name("sink"))
          val branches = (1 to 3).map(index => graph.addNode(NewIdentifier().name(s"branch$index")))
          val method   = graph.addNode(NewMethod().name("diamond").fullName("diamond"))
          val edges    = Cpg.newDiffGraphBuilder
          (branches ++ Seq(source, sink)).foreach { node =>
            edges.addEdge(method, node, EdgeTypes.AST)
            edges.addEdge(method, node, EdgeTypes.CONTAINS)
          }
          branches.foreach { branch =>
            edges.addEdge(source, branch, EdgeTypes.REACHING_DEF, "value")
            edges.addEdge(branch, sink, EdgeTypes.REACHING_DEF, "value")
          }
          edges.apply(graph)
          def query(bound: Int): (List[TableEntry], Set[String]) = {
            val diagnostics = new QueryDiagnostics
            val engine      = new Engine(
              EngineContext(config = EngineConfig(maxWitnessesPerEndpoint = bound, diagnostics = Some(diagnostics)))
            )
            try (engine.backwards(List(sink), List(source)), diagnostics.limitations)
            finally engine.shutdown()
          }
          val legacy   = query(1)
          val bounded  = query(2)
          val complete = query(4)
          if (language == "DART") {
            bounded._1.size shouldBe 2
            bounded._2 shouldBe Set("witness-alternatives")
            complete._1.size shouldBe 3
            complete._1.flatMap(_.path.map(_.node)).toSet should contain allElementsOf branches
            (1 to 3).foreach { _ => query(2)._1 shouldBe bounded._1 }
          } else {
            bounded shouldBe legacy
            complete shouldBe legacy
          }
          legacy._1.size shouldBe 1
          legacy._2 shouldBe empty
          complete._2 shouldBe empty
        } finally cpg.close()
      }
    }

    "retain shorter held-task routes without merging call contexts" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val graph  = cpg.graph
        val source = graph.addNode(NewIdentifier().name("source"))
        val join   = graph.addNode(NewIdentifier().name("join"))
        val middle = graph.addNode(NewIdentifier().name("middle"))
        val sink   = graph.addNode(NewIdentifier().name("sink"))
        val left   = graph.addNode(NewCall().name("left"))
        val right  = graph.addNode(NewCall().name("right"))
        val child  = TaskFingerprint(join, List(left), 1)
        val other  = TaskFingerprint(join, List(right), 1)
        val parent = TaskFingerprint(sink, Nil, 0)
        val routes = List(
          TableEntry(Vector(PathElement(source, List(left)), PathElement(join, List(left)))),
          TableEntry(
            Vector(PathElement(source, List(left)), PathElement(middle, List(left)), PathElement(join, List(left)))
          )
        )
        val table = mutable.Map(
          child -> (routes ++ routes),
          other -> List(TableEntry(Vector(PathElement(source, List(right)), PathElement(join, List(right)))))
        )
        val task      = ReachableByTask(List(parent, child), Vector(PathElement(sink)))
        val otherTask = ReachableByTask(List(parent, other), Vector(PathElement(sink)))
        new HeldTaskCompletion(List(task, otherTask), table, EngineConfig(maxWitnessesPerEndpoint = 4))
          .completeHeldTasks()
        table(child).size shouldBe 2
        table(parent).size shouldBe 3
        table(parent).map(_.path.head.callSiteStack).toSet shouldBe Set(List(left), List(right))
        table(parent).map(_.path.size).toSet shouldBe Set(3, 4)
      } finally cpg.close()
    }

    "report unfinished held-task combinations only for Dart" in {
      for (language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN")) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language(language))
        try {
          val graph     = cpg.graph
          val source    = graph.addNode(NewIdentifier().name("source"))
          val child     = graph.addNode(NewIdentifier().name("child"))
          val middle    = graph.addNode(NewIdentifier().name("middle"))
          val sink      = graph.addNode(NewIdentifier().name("sink"))
          val childKey  = TaskFingerprint(child, Nil, 0)
          val middleKey = TaskFingerprint(middle, Nil, 0)
          val sinkKey   = TaskFingerprint(sink, Nil, 0)
          val tasks     = List(
            ReachableByTask(List(middleKey, childKey), Vector(PathElement(middle))),
            ReachableByTask(List(sinkKey, middleKey), Vector(PathElement(sink)))
          )
          def solve(rounds: Int) = {
            val table       = mutable.Map(childKey -> List(TableEntry(Vector(PathElement(source), PathElement(child)))))
            val diagnostics = new QueryDiagnostics
            new HeldTaskCompletion(
              tasks,
              table,
              EngineConfig(maxHeldTaskIterations = rounds, diagnostics = Some(diagnostics))
            )
              .completeHeldTasks()
            (table, diagnostics.limitations)
          }
          val limited = solve(1)
          if (language == "DART") {
            limited._1.contains(sinkKey) shouldBe false
            limited._2 shouldBe Set("held-task-iterations")
          } else {
            limited._1(sinkKey).head.path.map(_.node) shouldBe Vector(source, child, middle, sink)
            limited._2 shouldBe empty
          }
          for (rounds <- Seq(0, 2)) {
            val complete = solve(rounds)
            complete._1(sinkKey).head.path.map(_.node) shouldBe Vector(source, child, middle, sink)
            complete._2 shouldBe empty
          }
        } finally cpg.close()
      }
    }

    "reject invalid witness and held-task bounds" in {
      for (bound <- Seq(0, -1)) {
        an[IllegalArgumentException] should be thrownBy EngineConfig(maxWitnessesPerEndpoint = bound)
      }
      an[IllegalArgumentException] should be thrownBy EngineConfig(maxHeldTaskIterations = -1)
    }
  }
}
