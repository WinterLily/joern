package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.joern.dataflowengineoss.queryengine.EngineContext
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, ParameterNode}
import io.joern.dataflowengineoss.semanticsloader.FlowPath.FlowMapping
import io.joern.x2cpg.X2Cpg
import io.joern.x2cpg.frontendspecific.DartLanguage
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files, Paths}
import ujson.Value

class DartMutationTests extends AnyWordSpec with Matchers {
  implicit val resolver: ICallResolver = NoResolve
  implicit val context: EngineContext  = EngineContext()
  private val repository               = Iterator
    .iterate(Paths.get(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.isRegularFile(path.resolve("project/Projects.scala")))
    .get
  private val config = Config(
    exporter =
      sys.env.getOrElse("DART_ASTGEN", repository.resolve("joern-cli/frontends/dartsrc2cpg/bin/dart_astgen").toString),
    sdk = sys.env.getOrElse("DART_SDK", repository.resolve("agents/toolchains/dart-sdk").toString)
  )

  private def exported(source: String)(check: Seq[Value] => Unit): Unit = {
    val directory = Files.createTempDirectory(Files.createDirectories(repository.resolve("agents")), "dart-mutation-")
    try {
      Files.writeString(directory.resolve("pubspec.yaml"), "name: mutation\nenvironment:\n  sdk: ^3.9.2\n")
      Files.writeString(directory.resolve("main.dart"), source)
      val units = ExportProtocol.units(ExporterRunner.run(config, directory, directory))
      units.foreach(unit => {
        unit("status").str shouldBe "resolved"
        unit("unsupportedKinds").arr shouldBe empty
      })
      check(units)
    } finally FileUtil.delete(directory)
  }

  private def graph(units: Seq[Value])(check: Cpg => Unit): Unit = {
    val cpg = Cpg.empty
    try {
      new MetaDataPass(cpg, DartLanguage.Name, repository.toString).createAndApply()
      new AstCreationPass(cpg, units, config).createAndApply()
      new MethodReferencePass(cpg, units).createAndApply()
      TypeNodePass.withTypesFromCpg(cpg).createAndApply()
      X2Cpg.applyDefaultOverlays(cpg)
      new OssDataFlow(new OssDataFlowOptions()).run(new LayerCreatorContext(cpg))
      check(cpg)
    } finally cpg.close()
  }

  private def contract(cpg: Cpg): Map[String, Boolean] = {
    val sinks = cpg.call.nameExact("sink").l.sortBy(_.lineNumber)
    Map(
      "reference" -> (cpg.method.nameExact("choose").ast.isIdentifier.nameExact("selected").refsTo.name.l == List(
        "selected"
      )),
      "target"  -> (cpg.call.nameExact("choose").callee.isExternal.l == List(false, false)),
      "binding" -> cpg.call
        .nameExact("choose")
        .l
        .forall(_.argument.argumentName("selected").argumentIndex.l == List(1)),
      "return" -> sinks.head.argument
        .reachableByFlows(cpg.method.nameExact("entry").parameter.nameExact("source"))
        .nonEmpty,
      "isolation" -> sinks.last.argument
        .reachableByFlows(cpg.method.nameExact("entry").parameter.nameExact("source"))
        .isEmpty
    )
  }

  "Dart qualification contracts" should {
    "detect removed references and targets, swapped bindings and broken return flow" in {
      exported("""String choose({required String selected, required String ignored}) => selected;
        |void sink(String value) {}
        |void entry(String source) {
        |  sink(choose(selected: source, ignored: 'fixed'));
        |  sink(choose(selected: 'fixed', ignored: source));
        |}
        |""".stripMargin) { original =>
        graph(original)(cpg => contract(cpg).values.forall(identity) shouldBe true)
        val mutations: Seq[(String, Seq[Value] => Unit)] = Seq(
          "reference" -> { nodes =>
            nodes
              .find(n => n("kind").str == "SimpleIdentifier" && n.obj.get("name").contains(ujson.Str("selected")))
              .get("reference") = ujson.Null
          },
          "target" -> { nodes =>
            nodes
              .filter(n => n("kind").str == "MethodInvocation" && n("target").str.endsWith(":FUNCTION:choose"))
              .foreach(_("target") = ujson.Null)
          },
          "binding" -> { nodes =>
            nodes.filter(n => n("kind").str == "ArgumentList" && n("bindings").arr.size == 2).foreach { n =>
              val bindings = n("bindings").arr
              val first    = bindings.head("parameter")
              bindings.head("parameter") = bindings.last("parameter")
              bindings.last("parameter") = first
            }
          },
          "return" -> { nodes =>
            val literal = nodes.find(n => n.obj.get("value").contains(ujson.Str("fixed"))).get
            nodes.find(_("kind").str == "ExpressionFunctionBody").get("children").arr.head("node") = literal("id")
          }
        )
        for ((name, mutate) <- mutations) {
          val units = original.map(unit => ujson.read(ujson.write(unit)))
          mutate(units.flatMap(_("nodes").arr))
          withClue(s"mutation $name: ") { graph(units)(cpg => contract(cpg)(name) shouldBe false) }
        }
      }
    }

    "detect a summary that invents receiver-to-constant-return flow" in {
      exported("""class Box {
        |  String echo() => value;
        |  String fixed() => 'fixed';
        |  String value = '';
        |}
        |void sink(String value) {}
        |void entry(Box source) { sink(source.echo()); sink(source.fixed()); }
        |""".stripMargin) { units =>
        graph(units) { cpg =>
          val sinks                        = cpg.call.nameExact("sink").l.sortBy(_.columnNumber)
          def flows(engine: EngineContext) = sinks.map(
            _.argument.reachableByFlows(cpg.method.nameExact("entry").parameter.nameExact("source"))(engine).nonEmpty
          )
          flows(context) shouldBe List(true, false)
          val target = cpg.method.nameExact("fixed").fullName.head
          val rules  = List(
            FlowSemantic(
              target,
              List(FlowMapping(ParameterNode(0), ParameterNode(0)), FlowMapping(ParameterNode(0), ParameterNode(-1)))
            )
          )
          val semantics = DefaultSemantics().plus(rules)
          semantics.initialize(cpg)
          flows(EngineContext(semantics = semantics)) shouldBe List(true, true)
        }
      }
    }
  }
}
