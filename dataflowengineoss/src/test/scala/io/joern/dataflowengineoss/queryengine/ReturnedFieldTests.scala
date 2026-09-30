package io.joern.dataflowengineoss.queryengine

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, Semantics}
import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.passes.base.{ContainsEdgePass, MethodDecoratorPass, MethodStubCreator}
import io.joern.x2cpg.passes.callgraph.StaticCallLinker
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes, EvaluationStrategies, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ReturnedFieldTests extends AnyWordSpec with Matchers {
  "Returned object fields" should {
    "bound repeated field projection by widening the suffix" in {
      val cpg = Cpg.empty
      try {
        implicit val semantics: Semantics = DefaultSemantics()
        val base   = cpg.graph.addNode(NewIdentifier().name("node").code("node").argumentIndex(1))
        val name   = cpg.graph.addNode(NewFieldIdentifier().canonicalName("next").code("next").argumentIndex(2))
        val access = cpg.graph.addNode(
          NewCall().name(Operators.fieldAccess).methodFullName(Operators.fieldAccess).code("node.next")
        )
        val edges = Cpg.newDiffGraphBuilder
        edges.addEdge(access, base, EdgeTypes.ARGUMENT)
        edges.addEdge(access, name, EdgeTypes.ARGUMENT)
        edges.addEdge(base, access, EdgeTypes.REACHING_DEF, "node")
        edges.apply(cpg.graph)
        val diagnostics = new QueryDiagnostics
        val config      = EngineConfig(maxFieldDepth = 2, diagnostics = Some(diagnostics))
        var demand      = List.empty[String]
        for (_ <- 1 to 10) {
          val result = Engine.expandIn(access, Vector(PathElement(access, fieldDemand = demand)), config = config)
          result.size shouldBe 1
          demand = result.head.fieldDemand
          demand.size should be <= 2
        }
        demand shouldBe List("next", "next")
        diagnostics.limitations shouldBe Set("field-depth-widening")
      } finally cpg.close()
    }
    "preserve field demands across calls and keep opaque summaries conservative" in {
      for (
        summarized <- Seq(false, true); copied <- Seq(false, true); captured <- Seq(false, true);
        replaced   <- Seq(false, true);
        language   <- Seq("DART", "C")
      ) {
        val cpg = Cpg.empty
        try {
          implicit val validation: ValidationMode = ValidationMode.Enabled
          implicit val semantics: Semantics       =
            if (summarized) DefaultSemantics().plus(List(FlowSemantic.from("make", List((1, 1), (1, -1)))))
            else DefaultSemantics()
          def parameter(name: String, index: Int) = NewMethodParameterIn()
            .name(name)
            .code(name)
            .index(index)
            .typeFullName("Object")
            .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          def read(name: String, declaration: NewNode): Ast = {
            val identifier = NewIdentifier().name(name).code(name).typeFullName("Object")
            Ast(identifier).withRefEdge(identifier, declaration)
          }
          def call(name: String, code: String, arguments: Seq[Ast]): Ast = {
            val node = NewCall()
              .name(name)
              .methodFullName(name)
              .code(code)
              .typeFullName("Object")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
            arguments.zipWithIndex.foldLeft(Ast(node)) { case (ast, (argument, index)) =>
              val root = argument.root.get.asInstanceOf[ExpressionNew]
              root.argumentIndex = index + 1
              root.order = index + 1
              ast.withChild(argument).withArgEdge(node, root)
            }
          }
          def field(base: Ast, name: String, code: String): Ast =
            call(Operators.fieldAccess, code, Seq(base, Ast(NewFieldIdentifier().canonicalName(name).code(name))))
          def returned(value: Ast): Ast = {
            val node = NewReturn().code("return " + value.root.get.asInstanceOf[ExpressionNew].code)
            Ast(node).withChild(value).withArgEdge(node, value.root.get)
          }
          def method(name: String, params: Seq[NewMethodParameterIn], body: Seq[Ast]): Ast =
            Ast(NewMethod().name(name).fullName(name).isExternal(false))
              .withChildren(params.map(Ast(_)))
              .withChild(Ast(NewBlock()).withChildren(body))
              .withChild(Ast(NewMethodReturn().code("RET").typeFullName("Object")))

          val value       = parameter("value", 1)
          val box         = NewLocal().name("box").code("box").typeFullName("Box")
          val alias       = NewLocal().name("alias").code("alias").typeFullName("Box")
          val independent = NewLocal().name("independent").code("independent").typeFullName("Box")
          val make        = method(
            "make",
            Seq(value),
            Seq(
              Ast(box),
              Ast(independent),
              call(Operators.assignment, "box = alloc", Seq(read("box", box), call(Operators.alloc, "alloc box", Nil))),
              call(
                Operators.assignment,
                "independent = alloc",
                Seq(read("independent", independent), call(Operators.alloc, "alloc independent", Nil))
              ),
              call(
                Operators.assignment,
                "box.value = value",
                Seq(field(read("box", box), "value", "box.value"), read("value", value))
              ),
              call(
                Operators.assignment,
                "independent.value = constant",
                Seq(
                  field(read("independent", independent), "value", "box.value"),
                  Ast(NewLiteral().code("constant").typeFullName("String"))
                )
              )
            ) ++ (if (captured)
                    Seq(
                      Ast(alias),
                      call(Operators.assignment, "alias = box", Seq(read("alias", alias), read("box", box)))
                    )
                  else Nil) ++ Seq(
              call(
                Operators.assignment,
                "box.other = constant",
                Seq(
                  field(if (captured) read("alias", alias) else read("box", box), "other", "box.other"),
                  if (copied) field(read("box", box), "value", "box.value")
                  else Ast(NewLiteral().code("constant").typeFullName("String"))
                )
              )
            ) ++ (if (replaced)
                    Seq(
                      call(
                        Operators.assignment,
                        "replace value",
                        Seq(
                          field(
                            if (captured) read("alias", alias) else read("box", box),
                            "value",
                            if (captured) "alias.value" else "box.value"
                          ),
                          Ast(NewLiteral().code("constant").typeFullName("String"))
                        )
                      )
                    )
                  else Nil) ++ Seq(returned(read("box", box)))
          )
          val forwarded = parameter("forwarded", 1)
          val inner     = call("make", "make(forwarded)", Seq(read("forwarded", forwarded)))
          val forward   = method("forward", Seq(forwarded), Seq(returned(inner)))
          val input     = parameter("input", 1)
          val safe      = parameter("safe", 2)
          val accesses  =
            for ((source, parameter) <- Seq("input" -> input, "safe" -> safe); name <- Seq("value", "other")) yield {
              val invocation = call("forward", s"forward($source)", Seq(read(source, parameter)))
              field(invocation, name, s"forward($source).$name")
            }
          val caller = method("caller", Seq(input, safe), accesses)
          val diff   = Cpg.newDiffGraphBuilder
          diff.addNode(NewMetaData().language(language).version("0.1"))
          Seq(make, forward, caller).foreach(Ast.storeInDiffGraph(_, diff))
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
              cpg.method.nameExact("caller").call.nameExact(Operators.fieldAccess).toList,
              cpg.method.nameExact("caller").parameter.toList
            )
            val endpoints = paths.map(p => p.path.head.node.code -> p.path.last.node.code).toSet
            val fields    = Seq(
              "value" -> (summarized || !replaced || captured && language == "C"),
              "other" -> (summarized || copied && (!captured || language == "DART"))
            ).collect { case (name, true) => name }
            withClue(s"$language summary=$summarized copy=$copied capture=$captured replace=$replaced") {
              endpoints shouldBe (for (source <- Seq("input", "safe"); field <- fields)
                yield source -> s"forward($source).$field").toSet
            }
          } finally engine.shutdown()
        } finally cpg.close()
      }
    }
  }
}
