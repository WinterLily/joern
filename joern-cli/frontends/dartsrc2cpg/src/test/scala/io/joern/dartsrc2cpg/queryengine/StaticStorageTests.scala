package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.*
import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, ModifierTypes, Operators}
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, DispatchTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StaticStorageTests extends AnyWordSpec with Matchers {
  "Static storage" should {
    "retain Dart exit modes through cleanup without changing foreign CFGs" in {
      for (language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN")) {
        val cpg = Cpg.empty
        try {
          implicit val validation: ValidationMode = ValidationMode.Enabled
          cpg.graph.addNode(NewMetaData().language(language))
          def block(children: Ast*): Ast = Ast(NewBlock()).withChildren(children)
          def thrown(code: String): Ast  = Ast(
            NewControlStructure()
              .code(code)
              .controlStructureType(ControlStructureTypes.THROW)
          ).withChild(Ast(NewLiteral().code("error")))
          def returned(code: String): Ast = Ast(NewReturn().code(code))
          def cleanup(code: String): Ast  = Ast(
            NewCall()
              .code(code)
              .name("cleanup")
              .methodFullName("cleanup")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
          )
          def protectedBody(body: Ast, finalBody: Ast): Ast = {
            val control = NewControlStructure().controlStructureType(ControlStructureTypes.TRY)
            Ast(control)
              .withChild(body)
              .withTryBodyEdge(control, body.root.get)
              .withChild(finalBody)
              .withFinallyBodyEdge(control, finalBody.root.get)
          }
          val diff = Cpg.newDiffGraphBuilder
          for (
            (name, body) <- Seq(
              "throwOnly"  -> thrown("throwOnly"),
              "resumed"    -> protectedBody(block(thrown("pending")), block(cleanup("resumed"))),
              "overridden" -> protectedBody(block(thrown("overriddenThrow")), block(returned("overridden")))
            )
          ) {
            Ast.storeInDiffGraph(
              Ast(NewMethod().name(name).fullName(name))
                .withChildren(Seq(block(body), Ast(NewMethodReturn().code("RET")))),
              diff
            )
          }
          diff.apply(cpg.graph)
          new CfgCreationPass(cpg).createAndApply()
          if (language == "DART") {
            cpg.methodReturn.tag.nameExact("dart.cfg.exit").value.toSet shouldBe Set("complete")
            cpg.controlStructure.codeExact("throwOnly").tag.value.toSet shouldBe Set("throw.before")
            cpg.call.codeExact("resumed").tag.value.toSet shouldBe Set("throw.after")
            cpg.ret.codeExact("overridden").tag.value.toSet shouldBe Set("normal.after")
            cpg.controlStructure.codeExact("overriddenThrow").tag.l shouldBe empty
          } else cpg.tag.l shouldBe empty
        } finally cpg.close()
      }
    }
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
