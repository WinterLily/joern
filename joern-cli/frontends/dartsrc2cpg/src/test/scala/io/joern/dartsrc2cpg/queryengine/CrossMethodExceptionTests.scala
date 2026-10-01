package io.joern.dartsrc2cpg.queryengine

import io.joern.dataflowengineoss.queryengine.*

import flatgraph.misc.TestUtils.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, Semantics}
import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.joern.x2cpg.passes.base.{ContainsEdgePass, MethodDecoratorPass}
import io.shiftleft.codepropertygraph.generated.{
  ControlStructureTypes,
  Cpg,
  DispatchTypes,
  EdgeTypes,
  EvaluationStrategies
}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CrossMethodExceptionTests extends AnyWordSpec with Matchers {
  "Exception call boundaries" should {
    "separate thrown values, normal results and repeated invocations" in {
      for (model <- Seq("body", "return summary", "no return summary")) {
        val cpg = Cpg.empty
        cpg.graph.addNode(NewMetaData().language("DART"))
        try {
          implicit val validation: ValidationMode = ValidationMode.Enabled
          implicit val semantics: Semantics       = model match {
            case "return summary" =>
              DefaultSemantics().plus(List(FlowSemantic.from("choose", List((1, 1), (2, 2), (2, -1)))))
            case "no return summary" => DefaultSemantics().plus(List(FlowSemantic.from("choose", List((1, 1), (2, 2)))))
            case _                   => DefaultSemantics()
          }
          def parameter(name: String, index: Int): NewMethodParameterIn = NewMethodParameterIn()
            .name(name)
            .code(s"Object $name")
            .typeFullName("Object")
            .index(index)
            .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          def read(declaration: NewMethodParameterIn, index: Int = 1): Ast = {
            val identifier =
              NewIdentifier().name(declaration.name).code(declaration.name).typeFullName("Object").argumentIndex(index)
            Ast(identifier).withRefEdge(identifier, declaration)
          }
          def block(children: Ast*): Ast = Ast(NewBlock()).withChildren(children)
          val thrownParameter            = parameter("thrownValue", 1)
          val normalParameter            = parameter("normalValue", 2)
          val thrown = NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code("throw thrownValue")
          val thrownValue = read(thrownParameter)
          val throwAst    = Ast(thrown).withChild(thrownValue).withArgEdge(thrown, thrownValue.root.get)
          val returned    = NewReturn().code("return normalValue")
          val normalValue = read(normalParameter)
          val returnAst   = Ast(returned).withChild(normalValue).withArgEdge(returned, normalValue.root.get)
          val branch      = NewControlStructure().controlStructureType(ControlStructureTypes.IF)
          val condition   = NewLiteral().code("condition").typeFullName("bool")
          val selection   = Ast(branch)
            .withChild(Ast(condition))
            .withConditionEdge(branch, condition)
            .withChild(throwAst)
            .withTrueBodyEdge(branch, thrown)
            .withChild(returnAst)
            .withFalseBodyEdge(branch, returned)
          val callee    = NewMethod().name("choose").fullName("choose").isExternal(false)
          val calleeAst = Ast(callee).withChildren(
            Seq(
              Ast(thrownParameter),
              Ast(normalParameter),
              block(selection),
              Ast(NewMethodReturn().code("RET").typeFullName("Object"))
            )
          )
          val errorInput  = parameter("errorInput", 1)
          val normalInput = parameter("normalInput", 2)
          val calls       = (1 to 2).map { index =>
            val call = NewCall()
              .name("choose")
              .methodFullName("choose")
              .code(s"choose@$index")
              .typeFullName("Object")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
            val error =
              if (index == 1) read(errorInput)
              else Ast(NewLiteral().code("constant").typeFullName("Object").argumentIndex(1))
            val normal  = read(normalInput, 2)
            val callAst = Ast(call)
              .withChildren(Seq(error, normal))
              .withArgEdge(call, error.root.get)
              .withArgEdge(call, normal.root.get)
            val caught  = NewControlStructure().controlStructureType(ControlStructureTypes.CATCH)
            val filter  = NewLiteral().code("true").typeFullName("bool")
            val channel = NewCall()
              .name("<operator>.caughtException")
              .methodFullName("<operator>.caughtException")
              .code(s"caught@$index")
              .typeFullName("Object")
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
            val handler   = Ast(caught).withChild(Ast(filter)).withConditionEdge(caught, filter).withChild(Ast(channel))
            val statement = NewControlStructure().controlStructureType(ControlStructureTypes.TRY)
            val body      = if (index == 1) callAst else block(callAst)
            val protectedCall = Ast(statement)
              .withChild(body)
              .withTryBodyEdge(statement, body.root.get)
              .withChild(handler)
              .withCatchBodyEdge(statement, caught)
            (call, protectedCall)
          }
          val caller    = NewMethod().name("caller").fullName("caller").isExternal(false)
          val callerAst = Ast(caller).withChildren(
            Seq(
              Ast(errorInput),
              Ast(normalInput),
              block(calls.map(_._2)*),
              Ast(NewMethodReturn().code("RET").typeFullName("void"))
            )
          )
          val diff = Cpg.newDiffGraphBuilder
          Ast.storeInDiffGraph(calleeAst, diff)
          Ast.storeInDiffGraph(callerAst, diff)
          calls.foreach { case (call, _) => diff.addEdge(call, callee, EdgeTypes.CALL) }
          diff.apply(cpg.graph)
          new MethodDecoratorPass(cpg).createAndApply()
          new ContainsEdgePass(cpg).createAndApply()
          new CfgCreationPass(cpg).createAndApply()
          new ReachingDefPass(cpg).createAndApply()
          val engine = new Engine(EngineContext(semantics = semantics))
          try {
            val sources   = cpg.method.nameExact("caller").parameter.toList
            val sinks     = cpg.call.code("(choose|caught)@[12]").toList
            val paths     = engine.backwards(sinks, sources)
            val endpoints = paths.map(path => path.path.head.node.code -> path.path.last.node.code).toSet
            val expected  = Set("Object errorInput" -> "caught@1") ++
              (if (model == "no return summary") Set.empty
               else Set("Object normalInput" -> "choose@1", "Object normalInput" -> "choose@2"))
            withClue(model) { endpoints shouldBe expected }
            engine.backwards(
              cpg.call.codeExact("caught@1").toList,
              cpg.call.codeExact("choose@1").toList
            ) shouldBe empty
          } finally engine.shutdown()
          val diagnostics = new QueryDiagnostics
          val limited     = new Engine(
            EngineContext(
              semantics = semantics,
              config = EngineConfig(maxCallDepth = 0, diagnostics = Some(diagnostics))
            )
          )
          try {
            limited.backwards(
              cpg.call.codeExact("caught@1").toList,
              cpg.method.nameExact("caller").parameter.nameExact("errorInput").toList
            ) shouldBe empty
            diagnostics.limitations should contain("call-depth")
          } finally limited.shutdown()
        } finally cpg.close()
      }
    }
    "report unavailable implicit and external exception payloads" in {
      val cpg = Cpg.empty
      cpg.graph.addNode(NewMetaData().language("DART"))
      try {
        for (
          (name, external, reason) <- Seq(
            ("<operator>.addition", false, "implicit-exception-channel"),
            ("unresolved", false, "unresolved-exception-channel"),
            ("external", true, "external-exception-channel")
          )
        ) {
          val call = cpg.graph.addNode(NewCall().name(name).methodFullName(name))
          if (external) {
            val method = cpg.graph.addNode(NewMethod().name(name).fullName(name).isExternal(true))
            val edges  = Cpg.newDiffGraphBuilder
            edges.addEdge(call, method, EdgeTypes.CALL)
            edges.apply(cpg.graph)
          }
          val diagnostics = new QueryDiagnostics
          val result      = ReachableByResult(
            List(TaskFingerprint(call, Nil, 0, OutputChannel.ExceptionValue)),
            Vector(PathElement(call, isOutputArg = true, outEdgeLabel = OutputChannel.ExceptionValue.edgeLabel))
          )
          new TaskCreator(EngineContext(config = EngineConfig(diagnostics = Some(diagnostics))))
            .createFromResults(Vector(result)) shouldBe empty
          diagnostics.limitations shouldBe Set(reason)
        }
      } finally cpg.close()
    }
  }
}
