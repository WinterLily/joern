package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.EngineContext
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.shiftleft.semanticcpg.validation.{PostFrontendValidator, ValidationLevel}
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files, Path, Paths}

class DartFrontendTests extends AnyWordSpec with Matchers {
  implicit val resolver: ICallResolver = NoResolve
  implicit val context: EngineContext  = EngineContext()
  private val repository               = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.isRegularFile(path.resolve("project/Projects.scala")))
    .get
  private val frontend = repository.resolve("joern-cli/frontends/dartsrc2cpg")
  private val config   = Config(
    exporter = sys.env.getOrElse("DART_ASTGEN", frontend.resolve("bin/dart_astgen").toString),
    sdk = sys.env.getOrElse("DART_SDK", repository.resolve("agents/toolchains/dart-sdk").toString)
  ).withSchemaValidation(ValidationMode.Enabled)

  private def fixture(helper: String, main: String, dataflow: Boolean = true)(check: (Cpg, Path) => Unit): Unit = {
    val dir = Files.createTempDirectory(Files.createDirectories(repository.resolve("agents")), "dart-cpg-")
    try {
      Files.createDirectories(dir.resolve("lib"))
      Files.createDirectories(dir.resolve("bin"))
      Files.writeString(dir.resolve("pubspec.yaml"), "name: proof\nenvironment:\n  sdk: ^3.9.2\n")
      Files.writeString(dir.resolve("lib/helper.dart"), helper)
      Files.writeString(
        dir.resolve("bin/main.dart"),
        "import '../lib/helper.dart';\nvoid sink(String value) {}\n" + main
      )
      val graph = new DartSrc2Cpg()
        .createCpg(config.withInputPath(dir.toString).withOutputPath(dir.resolve("cpg.bin").toString))
        .get
      try {
        new PostFrontendValidator(graph, ValidationLevel.V3).createAndApply()
        X2Cpg.applyDefaultOverlays(graph)
        if (dataflow) new OssDataFlow(new OssDataFlowOptions()).run(new LayerCreatorContext(graph))
        check(graph, dir)
      } finally graph.close()
    } finally FileUtil.delete(dir)
  }

  private def assertFlow(cpg: Cpg, expected: Boolean): Unit = {
    val paths = cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).l
    paths.nonEmpty shouldBe expected
    if (expected) paths.exists(_.elements.exists {
      case p: io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn => p.name == "value"
      case _                                                                   => false
    }) shouldBe true
  }

  "Dart frontend" should {
    "link two files and preserve the dataflow proof after saving and reloading" in {
      fixture(
        "String relay(String value) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
      ) { (cpg, dir) =>
        cpg.metaData.language.l shouldBe List("DART")
        cpg.call.nameExact("relay").callee.filename.l shouldBe List("lib/helper.dart")
        cpg.identifier.nameExact("input").refsTo.name.toSet shouldBe Set("input")
        assertFlow(cpg, true)
        cpg.close()
        val reloaded = Cpg.withStorage(dir.resolve("cpg.bin"))
        try assertFlow(reloaded, true)
        finally reloaded.close()
      }
    }
    "reject flow through a constant argument" in {
      fixture(
        "String relay(String value) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay('constant')); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
      }
    }
    "bind reordered named arguments without changing evaluation order" in {
      fixture(
        "String relay({required String value, required String ignored}) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay(ignored: 'constant', value: input)); }"
      ) { (cpg, _) =>
        val arguments = cpg.call.nameExact("relay").argument.l.sortBy(_.order)
        arguments.map(_.argumentIndex) shouldBe List(2, 1)
        arguments.map(_.argumentName) shouldBe List(Some("ignored"), Some("value"))
        arguments.head.cfgNext.l should contain(arguments(1))
        assertFlow(cpg, true)
      }
    }
    "reject flow from a named argument bound to the unused parameter" in {
      fixture(
        "String relay({required String value, required String ignored}) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay(ignored: input, value: 'constant')); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
      }
    }
    "kill flow when a local is overwritten" in {
      fixture(
        "String relay(String value) { return value; }",
        "void main(List<String> args) { final input = args[0]; var value = input; value = 'constant'; sink(relay(value)); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
      }
    }
    "preserve Unicode source snippets and columns" in {
      fixture("String relay(String value) => value;", "void main() { final emoji = '😀'; sink(relay('é')); }") {
        (cpg, _) =>
          cpg.literal.codeExact("'😀'").size shouldBe 1
          cpg.call.nameExact("sink").code.l shouldBe List("sink(relay('é'))")
          cpg.call.nameExact("sink").columnNumber.l shouldBe List(35)
      }
    }
    "retain unresolved calls in partial graphs" in {
      fixture("String relay(String value) => value;", "void main() { missing('value'); }", dataflow = false) {
        (cpg, _) =>
          cpg.call.nameExact("missing").methodFullName.l shouldBe List("<unresolved>.missing")
          cpg.call.nameExact("missing").argument.code.l shouldBe List("'value'")
      }
    }
    "reject incompatible and truncated exporter output" in {
      val header = ujson.Obj("record" -> "header", "protocolVersion" -> 2)
      intercept[IllegalArgumentException](ExportProtocol.units(Seq(header)))
      intercept[IllegalArgumentException](ExportProtocol.units(Seq.empty))
      val valid = ujson.Obj(
        "record"          -> "header",
        "protocolVersion" -> 1,
        "offsetEncoding"  -> "utf-16",
        "analyzerVersion" -> "8.4.1",
        "sdkVersion"      -> "3.9.2"
      )
      intercept[IllegalArgumentException](ExportProtocol.units(Seq(valid)))
      intercept[IllegalArgumentException](
        ExportProtocol.units(Seq(valid, ujson.Obj("record" -> "summary", "files" -> 1)))
      )
    }
  }
}
