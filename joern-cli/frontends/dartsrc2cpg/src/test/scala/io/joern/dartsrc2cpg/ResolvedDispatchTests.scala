package io.joern.dartsrc2cpg

import flatgraph.misc.TestUtils.*
import io.joern.x2cpg.frontendspecific.DartLanguage
import io.joern.x2cpg.passes.callgraph.{DynamicCallLinker, StaticCallLinker}
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ResolvedDispatchTests extends AnyWordSpec with Matchers {
  private implicit val resolver: ICallResolver = NoResolve
  "Resolved Dart dispatch" should {
    "preserve adapted static targets only on marked Dart calls" in {
      for (
        language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN"); marked <- Seq(false, true);
        dispatch <- Seq(DispatchTypes.STATIC_DISPATCH, DispatchTypes.INLINED)
      ) {
        val cpg = Cpg.empty
        try {
          cpg.graph.addNode(NewMetaData().language(language))
          val original = cpg.graph.addNode(NewMethod().name("original").fullName("original"))
          val chosen   = cpg.graph.addNode(NewMethod().name("chosen").fullName("chosen"))
          val call     = cpg.graph.addNode(NewCall().name("original").methodFullName("original").dispatchType(dispatch))
          val diff     = Cpg.newDiffGraphBuilder
          diff.addEdge(call, chosen, EdgeTypes.CALL)
          if (marked) {
            val tag = cpg.graph.addNode(NewTag().name(DartLanguage.ResolvedDispatchTag).value("defaultArguments"))
            diff.addEdge(call, tag, EdgeTypes.TAGGED_BY)
          }
          diff.apply(cpg.graph)
          new StaticCallLinker(cpg).createAndApply()
          val expected = if (language == "DART" && marked) Set("chosen") else Set("chosen", "original")
          withClue(s"$language marked=$marked $dispatch") { call.callee.fullName.toSet shouldBe expected }
        } finally cpg.close()
      }
    }
    "leave other languages and unmarked Dart calls under existing linking rules" in {
      for (language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN"); marked <- Seq(false, true)) {
        val cpg = Cpg.empty
        try {
          val graph = cpg.graph
          graph.addNode(NewMetaData().language(language))
          val base   = graph.addNode(NewTypeDecl().name("Base").fullName("Base"))
          val child  = graph.addNode(NewTypeDecl().name("Child").fullName("Child"))
          val typ    = graph.addNode(NewType().name("Base").fullName("Base").typeDeclFullName("Base"))
          val method =
            graph.addNode(NewMethod().name("echo").fullName("Base.echo").signature("String(1)").isExternal(false))
          val overrideMethod =
            graph.addNode(NewMethod().name("echo").fullName("Child.echo").signature("String(1)").isExternal(false))
          val call = graph.addNode(
            NewCall().name("echo").methodFullName("Base.echo").dispatchType(DispatchTypes.DYNAMIC_DISPATCH)
          )
          val diff = Cpg.newDiffGraphBuilder
          diff.addEdge(base, method, EdgeTypes.AST)
          diff.addEdge(child, overrideMethod, EdgeTypes.AST)
          diff.addEdge(typ, base, EdgeTypes.REF)
          diff.addEdge(child, typ, EdgeTypes.INHERITS_FROM)
          diff.addEdge(call, method, EdgeTypes.CALL)
          if (marked) {
            val tag = graph.addNode(NewTag().name(DartLanguage.ResolvedDispatchTag).value("analyzer"))
            diff.addEdge(call, tag, EdgeTypes.TAGGED_BY)
          }
          diff.apply(graph)
          new DynamicCallLinker(cpg).createAndApply()
          val expected =
            if (language == "DART" && marked) Set(method.fullName) else Set(method.fullName, overrideMethod.fullName)
          withClue(s"$language marked=$marked") { call.callee.fullName.toSet shouldBe expected }
        } finally cpg.close()
      }
    }
  }
}
