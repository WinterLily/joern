package io.joern.dartsrc2cpg.queryengine

import io.joern.dataflowengineoss.queryengine.*

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.passes.base.{ContainsEdgePass, MethodDecoratorPass, MethodStubCreator}
import io.joern.x2cpg.passes.callgraph.StaticCallLinker
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EvaluationStrategies, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BlockValueTests extends AnyWordSpec with Matchers {
  "Expression blocks" should {
    "propagate only the final value through nested blocks in returns and arguments" in {
      for (
        language <- Seq("C", "JAVASCRIPT", "JAVA", "KOTLIN", "DART"); depth <- 1 to 3; dependent <- Seq(false, true);
        argument <- Seq(false, true)
      ) {
        val cpg = Cpg.empty

        try {
          implicit val validation: ValidationMode = ValidationMode.Enabled
          implicit val semantics: Semantics       = DefaultSemantics()
          val input                               = NewMethodParameterIn()
            .name("input")
            .code("input")
            .index(1)
            .typeFullName("int")
            .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          def read(): Ast = {
            val node = NewIdentifier().name("input").code("input").typeFullName("int")
            Ast(node).withRefEdge(node, input)
          }
          val finalValue = if (dependent) read() else Ast(NewLiteral().code("0").typeFullName("int"))
          val value      = (1 to depth).foldLeft(finalValue) { (inner, _) =>
            Ast(NewBlock().code("{ input; value }").typeFullName("int")).withChildren(Seq(read(), inner))
          }
          val returned = if (argument) {
            val call = NewCall()
              .name(Operators.plus)
              .methodFullName(Operators.plus)
              .code("+block")
              .typeFullName("int")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
            value.root.get.asInstanceOf[ExpressionNew].argumentIndex = 1
            Ast(call).withChild(value).withArgEdge(call, value.root.get)
          } else value
          val ret    = NewReturn().code("return value")
          val method = Ast(NewMethod().name("choose").fullName("choose").isExternal(false))
            .withChild(Ast(input))
            .withChild(Ast(NewBlock()).withChild(Ast(ret).withChild(returned).withArgEdge(ret, returned.root.get)))
            .withChild(Ast(NewMethodReturn().typeFullName("int").code("RET")))
          val diff = Cpg.newDiffGraphBuilder
          diff.addNode(NewMetaData().language(language).version("0.1"))
          Ast.storeInDiffGraph(method, diff)
          diff.apply(cpg.graph)
          new MethodStubCreator(cpg).createAndApply()
          new MethodDecoratorPass(cpg).createAndApply()
          new ContainsEdgePass(cpg).createAndApply()
          new StaticCallLinker(cpg).createAndApply()
          new CfgCreationPass(cpg).createAndApply()
          new ReachingDefPass(cpg).createAndApply()
          val engine = new Engine(EngineContext(semantics = semantics))
          try {
            val paths = engine.backwards(
              cpg.method.nameExact("choose").ast.isReturn.l,
              cpg.method.nameExact("choose").parameter.l
            )
            withClue(s"$language depth=$depth dependent=$dependent argument=$argument: ") {
              paths.nonEmpty shouldBe (dependent && language == "DART")
            }
          } finally engine.shutdown()
        } finally cpg.close()
      }
    }
  }
}
