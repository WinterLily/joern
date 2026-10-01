package io.joern.dartsrc2cpg.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.queryengine.Engine
import io.joern.x2cpg.passes.base.MethodDecoratorPass
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NamedArgumentTests extends AnyWordSpec with Matchers {
  "Named output arguments" should {
    "use Dart names while preserving other languages and opaque external placeholders" in {
      for (
        language <- Seq("DART", "C", "JAVA", "JAVASCRIPT", "KOTLIN"); external <- Seq(false, true);
        name     <- Seq(None, Some("second"), Some("absent")); opaque          <- Seq(false, true)
      ) {
        val cpg = Cpg.empty
        try {
          cpg.graph.addNode(NewMetaData().language(language))
          val method = cpg.graph.addNode(NewMethod().name("write").fullName("write").isExternal(external))
          val first  = cpg.graph.addNode(
            NewMethodParameterIn().name(if (opaque) "p1" else "first").code(if (opaque) "p1" else "first").index(1)
          )
          val second = cpg.graph.addNode(
            NewMethodParameterIn().name(if (opaque) "p2" else "second").code(if (opaque) "p2" else "second").index(2)
          )
          val call     = cpg.graph.addNode(NewCall().name("write").methodFullName("write"))
          val argument = cpg.graph.addNode(NewIdentifier().name("value").argumentIndex(1).argumentName(name))
          val diff     = Cpg.newDiffGraphBuilder
          Seq(first, second).foreach(diff.addEdge(method, _, EdgeTypes.AST))
          diff.addEdge(call, method, EdgeTypes.CALL)
          diff.addEdge(call, argument, EdgeTypes.ARGUMENT)
          diff.apply(cpg.graph)
          new MethodDecoratorPass(cpg).createAndApply()
          val expected =
            if (language != "DART" || name.isEmpty || (external && opaque)) Set(first.name)
            else if (!opaque && name.contains("second")) Set("second")
            else Set.empty[String]
          withClue(s"$language external=$external opaque=$opaque name=$name") {
            Engine.argToOutputParams(argument).name.toSet shouldBe expected
          }
        } finally cpg.close()
      }
    }
  }
}
