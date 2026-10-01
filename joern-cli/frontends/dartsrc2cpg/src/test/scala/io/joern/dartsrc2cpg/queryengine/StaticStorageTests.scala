package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, ModifierTypes, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StaticStorageTests extends AnyWordSpec with Matchers {
  "Static storage" should {
    "supply a Dart prerequisite without changing foreign or instance-field traversal" in {
      for (language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN"); instance <- Seq(false, true)) {
        val cpg = Cpg.empty
        try {
          val graph = cpg.graph
          graph.addNode(NewMetaData().language(language))
          val owner      = graph.addNode(NewTypeDecl().name("Storage").fullName("Storage"))
          val member     = graph.addNode(NewMember().name("value"))
          val modifier   = graph.addNode(NewModifier().modifierType(ModifierTypes.STATIC))
          val method     = graph.addNode(NewMethod().name("example").fullName("example"))
          val source     = graph.addNode(NewIdentifier().name("input").argumentIndex(2))
          val assignment = graph.addNode(NewCall().name(Operators.assignment))
          val edges      = Cpg.newDiffGraphBuilder
          edges.addEdge(owner, member, EdgeTypes.AST)
          edges.addEdge(member, modifier, EdgeTypes.AST)
          def access(index: Int): Call = {
            val call     = graph.addNode(NewCall().name(Operators.fieldAccess).argumentIndex(index))
            val receiver = graph.addNode(
              NewIdentifier()
                .name(if (instance) "this" else "Storage")
                .typeFullName("Storage")
                .argumentIndex(1)
            )
            val field = graph.addNode(NewFieldIdentifier().canonicalName("value").argumentIndex(2))
            for (argument <- Seq(receiver, field)) {
              edges.addEdge(call, argument, EdgeTypes.AST)
              edges.addEdge(call, argument, EdgeTypes.ARGUMENT)
            }
            call
          }
          val written = access(1)
          val read    = access(-1)
          for (argument <- Seq(written, source)) {
            edges.addEdge(assignment, argument, EdgeTypes.AST)
            edges.addEdge(assignment, argument, EdgeTypes.ARGUMENT)
          }
          for (node <- Seq(assignment, read)) edges.addEdge(method, node, EdgeTypes.AST)
          for (node <- Seq(assignment, written, source, read)) edges.addEdge(method, node, EdgeTypes.CONTAINS)
          edges.addEdge(method, assignment, EdgeTypes.CFG)
          edges.addEdge(assignment, read, EdgeTypes.CFG)
          edges.apply(graph)
          val diagnostics = new QueryDiagnostics
          val engine      = new Engine(EngineContext(config = EngineConfig(diagnostics = Some(diagnostics))))
          try {
            val paths = engine.backwards(List(read), List(source))
            withClue(s"$language instance=$instance") {
              paths.nonEmpty shouldBe (language == "DART" && !instance)
              diagnostics.limitations shouldBe empty
            }
          } finally engine.shutdown()
        } finally cpg.close()
      }
    }
    "reject an unusable storage-search budget" in {
      intercept[IllegalArgumentException](EngineConfig(maxStaticStorageNodes = 0))
    }
  }
}
