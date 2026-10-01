package io.joern.dataflowengineoss.queryengine

import flatgraph.misc.TestUtils.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

class BoundedWitnessOrderingTests extends AnyWordSpec with Matchers {
  "Bounded Dart witness ordering" should {
    "avoid scoring shorter routes once longer routes fill the bound" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val source = cpg.graph.addNode(NewIdentifier().name("source"))
        val sink   = cpg.graph.addNode(NewIdentifier().name("sink"))
        val middle = (1 to 3).map(i => cpg.graph.addNode(NewIdentifier().name(s"middle$i")))
        def route(nodes: Identifier*): Vector[PathElement] = nodes.toVector.map(PathElement(_))
        val candidates                                     = List(
          "z"  -> route(source, middle(0), middle(1), sink),
          "a"  -> route(source, middle(1), middle(0), sink),
          "b"  -> route(source, middle(0), middle(2), sink),
          "0"  -> route(source, middle(0), sink),
          "00" -> route(source, sink)
        )
        val scored      = mutable.ArrayBuffer.empty[String]
        val diagnostics = new QueryDiagnostics
        val selected    = WitnessSelection.select[(String, Vector[PathElement])](
          candidates,
          _._2,
          EngineConfig(maxWitnessesPerEndpoint = 2, diagnostics = Some(diagnostics)),
          candidate => { scored += candidate._1; candidate._1 }
        )
        selected.map(_._1) shouldBe List("a", "b")
        scored.toSet shouldBe Set("z", "a", "b")
        scored.size shouldBe 3
        diagnostics.limitations shouldBe Set("witness-alternatives")
      } finally cpg.close()
    }

    "select the smallest task context before extending into shorter route groups" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val source      = cpg.graph.addNode(NewIdentifier().name("source"))
        val sink        = cpg.graph.addNode(NewIdentifier().name("sink"))
        val middle      = cpg.graph.addNode(NewIdentifier().name("middle"))
        val extra       = cpg.graph.addNode(NewIdentifier().name("extra"))
        val left        = cpg.graph.addNode(NewCall().name("left"))
        val right       = cpg.graph.addNode(NewCall().name("right"))
        val long        = Vector(source, middle, extra, sink).map(PathElement(_))
        val short       = Vector(source, middle, sink).map(PathElement(_))
        val tiny        = Vector(source, sink).map(PathElement(_))
        val leftResult  = ReachableByResult(List(TaskFingerprint(sink, List(left), 1)), long)
        val rightResult = ReachableByResult(List(TaskFingerprint(sink, List(right), 1)), long)
        val shortResult = ReachableByResult(List(TaskFingerprint(sink, Nil, 1)), short)
        val tinyResult  = ReachableByResult(List(TaskFingerprint(sink, Nil, 1)), tiny)
        def key(result: ReachableByResult): String =
          if (result == leftResult) "a" else if (result == rightResult) "z" else "b"
        for (bound <- Seq(2, 4)) {
          val diagnostics = new QueryDiagnostics
          val selected    = WitnessSelection.select[ReachableByResult](
            List(rightResult, shortResult, leftResult, rightResult, tinyResult),
            _.path,
            EngineConfig(maxWitnessesPerEndpoint = bound, diagnostics = Some(diagnostics)),
            key
          )
          selected shouldBe (if (bound == 2) List(leftResult, shortResult)
                             else List(leftResult, shortResult, tinyResult))
          diagnostics.limitations shouldBe (if (bound == 2) Set("witness-alternatives") else Set.empty)
        }
        val diagnostics = new QueryDiagnostics
        WitnessSelection.select[ReachableByResult](
          List(rightResult, leftResult, rightResult),
          _.path,
          EngineConfig(maxWitnessesPerEndpoint = 2, diagnostics = Some(diagnostics)),
          key
        ) shouldBe List(leftResult)
        diagnostics.limitations shouldBe empty
      } finally cpg.close()
    }

    "avoid ranking isolated path lengths" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val source = cpg.graph.addNode(NewIdentifier().name("source"))
        val sink   = cpg.graph.addNode(NewIdentifier().name("sink"))
        val middle = cpg.graph.addNode(NewIdentifier().name("middle"))
        val extra  = cpg.graph.addNode(NewIdentifier().name("extra"))
        val routes = List(Vector(source, sink), Vector(source, middle, extra, sink), Vector(source, middle, sink))
          .map(_.map(PathElement(_)))
        val diagnostics = new QueryDiagnostics
        WitnessSelection.select[Vector[PathElement]](
          routes,
          identity,
          EngineConfig(maxWitnessesPerEndpoint = 2, diagnostics = Some(diagnostics)),
          _ => fail("Different lengths need no tie breaker")
        ) shouldBe List(routes(1), routes(2))
        diagnostics.limitations shouldBe Set("witness-alternatives")
      } finally cpg.close()
    }

    "avoid ranking identical table entries" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val source      = cpg.graph.addNode(NewIdentifier().name("source"))
        val sink        = cpg.graph.addNode(NewIdentifier().name("sink"))
        val entry       = TableEntry(Vector(PathElement(source, isOutputArg = true), PathElement(sink)))
        val diagnostics = new QueryDiagnostics
        WitnessSelection.select[TableEntry](
          List(entry, entry.copy()),
          _.path,
          EngineConfig(maxWitnessesPerEndpoint = 2, diagnostics = Some(diagnostics)),
          _ => fail("Identical entries need no ranking key")
        ) shouldBe List(entry)
        diagnostics.limitations shouldBe empty
      } finally cpg.close()
    }

    "keep stable ties between distinct paths and preserve task flags" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        val source   = cpg.graph.addNode(NewIdentifier().name("source"))
        val sink     = cpg.graph.addNode(NewIdentifier().name("sink"))
        val variants = List(
          PathElement(source, fieldDemand = List("left")),
          PathElement(source, fieldDemand = List("right")),
          PathElement(source, isOutputArg = true)
        ).map(start => Vector(start, PathElement(sink)))
        val diagnostics = new QueryDiagnostics
        WitnessSelection.select[Vector[PathElement]](
          variants ++ variants,
          identity,
          EngineConfig(maxWitnessesPerEndpoint = 2, diagnostics = Some(diagnostics)),
          _ => "same key"
        ) shouldBe variants.take(2)
        diagnostics.limitations shouldBe Set("witness-alternatives")
      } finally cpg.close()
    }
  }
}
