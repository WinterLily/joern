package io.joern.dartsrc2cpg.queryengine

import io.joern.dataflowengineoss.queryengine.*

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.firstIdentifierFromCapturedScopes
import io.joern.x2cpg.passes.base.ContainsEdgePass
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CapturedBindingTests extends AnyWordSpec with Matchers {
  "Captured bindings" should {
    "follow identities through nested scopes and stop at replacements on each CFG route" in {
      for (
        language    <- Seq("C", "JAVASCRIPT", "JAVA", "KOTLIN", "DART"); parameter <- Seq(false, true);
        conditional <- Seq(false, true)
      ) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language(language))
        try {
          val graph               = cpg.graph
          val source: Declaration =
            if (parameter) graph.addNode(NewMethodParameterIn().name("input"))
            else graph.addNode(NewLocal().name("input"))
          val edges                                                                    = Cpg.newDiffGraphBuilder
          def closure(name: String, captured: Declaration): (Method, Local, MethodRef) = {
            val method    = graph.addNode(NewMethod().name(name).fullName(name))
            val body      = graph.addNode(NewBlock())
            val proxy     = graph.addNode(NewLocal().name("input").closureBindingId(name + ":input"))
            val binding   = graph.addNode(NewClosureBinding().closureBindingId(name + ":input"))
            val reference = graph.addNode(NewMethodRef().methodFullName(name))
            edges.addEdge(method, body, EdgeTypes.AST)
            edges.addEdge(body, proxy, EdgeTypes.AST)
            edges.addEdge(reference, method, EdgeTypes.REF)
            edges.addEdge(reference, binding, EdgeTypes.CAPTURE)
            edges.addEdge(binding, captured, EdgeTypes.REF)
            (method, proxy, reference)
          }
          def identifier(method: Method, declaration: Declaration): Identifier = {
            val node = graph.addNode(NewIdentifier().name(declaration.name).lineNumber(2))
            edges.addEdge(method, node, EdgeTypes.AST)
            edges.addEdge(node, declaration, EdgeTypes.REF)
            node
          }
          val (outer, proxy, _)                     = closure("outer", source)
          val (inner, nestedProxy, nestedReference) = closure("inner", proxy)
          edges.addEdge(outer, nestedReference, EdgeTypes.AST)
          val nestedRead = identifier(inner, nestedProxy)
          edges.addEdge(inner, nestedRead, EdgeTypes.CFG)
          val shadow = graph.addNode(NewLocal().name("input"))
          edges.addEdge(outer, shadow, EdgeTypes.AST)
          val unrelated = identifier(outer, shadow)
          graph.applyDiff(_.setNodeProperty(unrelated, "LINE_NUMBER", 1))
          val readBefore  = identifier(outer, proxy)
          val target      = graph.addNode(NewIdentifier().name("input").argumentIndex(1).lineNumber(2))
          val constant    = graph.addNode(NewLiteral().code("constant").argumentIndex(2))
          val replacement = graph.addNode(NewCall().name(Operators.assignment))
          edges.addEdge(outer, replacement, EdgeTypes.AST)
          Seq(target, constant).foreach { argument =>
            edges.addEdge(replacement, argument, EdgeTypes.AST)
            edges.addEdge(replacement, argument, EdgeTypes.ARGUMENT)
          }
          edges.addEdge(target, proxy, EdgeTypes.REF)
          val readAfter                           = identifier(outer, proxy)
          val (later, laterProxy, laterReference) = closure("later", proxy)
          edges.addEdge(outer, laterReference, EdgeTypes.AST)
          val laterRead = identifier(later, laterProxy)
          edges.addEdge(later, laterRead, EdgeTypes.CFG)
          Seq(outer, unrelated, readBefore, nestedReference, target, constant, replacement, readAfter, laterReference)
            .sliding(2)
            .foreach { pair => edges.addEdge(pair.head, pair.last, EdgeTypes.CFG) }
          if (conditional) edges.addEdge(nestedReference, readAfter, EdgeTypes.CFG)
          val (modeled, _, _) = closure("modeledInput", source)
          val modeledLocal    = graph.addNode(NewLocal().name("element"))
          edges.addEdge(modeled, modeledLocal, EdgeTypes.AST)
          val modeledRead = identifier(modeled, modeledLocal)
          edges.addEdge(modeled, modeledRead, EdgeTypes.CFG)
          edges.apply(graph)
          new ContainsEdgePass(cpg).createAndApply()
          val expected =
            if (language != "DART") Set(unrelated)
            else
              Set(readBefore, nestedRead) ++
                (if (conditional) Set(readAfter, laterRead) else Set.empty) ++
                (if (parameter) Set(modeledRead) else Set.empty)
          withClue(s"$language parameter=$parameter conditional=$conditional: ") {
            firstIdentifierFromCapturedScopes(source, includeModeledInputs = true).toSet shouldBe expected
            firstIdentifierFromCapturedScopes(source).toSet shouldBe expected - modeledRead
          }
        } finally cpg.close()
      }
    }
  }
}
