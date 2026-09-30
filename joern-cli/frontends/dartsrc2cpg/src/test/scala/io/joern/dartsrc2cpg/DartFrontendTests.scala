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

  private def fixture(
    helper: String,
    main: String,
    dataflow: Boolean = true,
    extraFiles: Map[String, String] = Map.empty
  )(check: (Cpg, Path) => Unit): Unit = {
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
      extraFiles.foreach { case (name, source) =>
        Files.createDirectories(dir.resolve(name).getParent)
        Files.writeString(dir.resolve(name), source)
      }
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
    "model classes, inheritance, generics, accessors and receivers" in {
      fixture(
        """abstract class Base { String relay(String value); }
        |class Box<T> extends Base {
        |  T? item;
        |  static String label = 'box';
        |  Box(this.item);
        |  String relay(String value) => value;
        |  T? get value => item;
        |  set value(T? v) { item = v; }
        |  static String identity(String value) => value;
        |}""".stripMargin,
        "void main(List<String> args) { final input = args[0]; final box = Box<String>(input); sink(box.relay(input)); sink(Box.identity('constant')); }"
      ) { (cpg, _) =>
        cpg.typeDecl.nameExact("Box").inheritsFromTypeFullName.l.exists(_.endsWith(":CLASS:Base")) shouldBe true
        cpg.member.nameExact("item").typeFullName.l shouldBe List("T?")
        cpg.method.nameExact("relay").isExternal(false).parameter.index(0).name.toSet shouldBe Set("this")
        cpg.call.nameExact("relay").argument(0).code.l shouldBe List("box")
        cpg.call.nameExact("identity").argument.argumentIndex.l shouldBe List(1)
        cpg.method.nameExact("value").size should be >= 2
        cpg.unknown.size shouldBe 0
        assertFlow(cpg, true)
      }
    }
    "bind defaults and positional optional arguments" in {
      fixture(
        "String relay(String value, [String ignored = 'default']) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
      ) { (cpg, _) =>
        cpg.call.nameExact("relay").argument(2).code.l shouldBe List("'default'")
        cpg.method.nameExact("relay").parameter.index(2).code.l shouldBe List("String ignored = 'default'")
        assertFlow(cpg, true)
      }
      fixture(
        "String relay({String value = 'default', String? ignored}) => value;",
        "void main(List<String> args) { final input = args[0]; sink(relay(ignored: input)); }"
      ) { (cpg, _) =>
        cpg.call.nameExact("relay").argument(1).code.l shouldBe List("'default'")
        assertFlow(cpg, false)
      }
    }
    "retain named, factory, redirecting and initializing constructors" in {
      fixture(
        """class Box {
        | String value;
        | Box(this.value);
        | Box.named(String x) : value = x;
        | Box.redirect(String x) : this.named(x);
        | factory Box.make(String x) = Box.named;
        | factory Box.create(String x) { return Box(x); }
        |}""".stripMargin,
        "void main() { Box('a'); Box.named('b'); Box.redirect('c'); Box.make('d'); Box.create('e'); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.method.isExternal(false).name.toSet should contain allOf ("<init>", "named", "redirect", "make", "create")
        cpg.call.nameExact("named").callee.isExternal.l should not contain true
        cpg.call.nameExact("<operator>.fieldAccess").argument(2).code.toSet should contain("value")
        cpg.unknown.size shouldBe 0
      }
    }
    "create CFG branches, loop back edges and switch targets" in {
      fixture(
        "String relay(String value) => value;",
        """void main(List<String> args) {
        | final input = args[0]; var value = 'constant';
        | if (args.isNotEmpty) { value = input; } else { value = 'other'; }
        | while (value.isEmpty) { value = input; break; }
        | do { value = input; } while (value.isEmpty);
        | for (var i = 0; i < 2; i++) { if (i == 1) continue; value = input; }
        | for (final item in args) { value = item; }
        | switch (value) { case 'x': value = input; break; default: value = input; }
        | sink(relay(value));
        |}""".stripMargin
      ) { (cpg, _) =>
        cpg.controlStructure.controlStructureType.toSet should contain allOf (
          "IF",
          "WHILE",
          "DO",
          "FOR",
          "SWITCH",
          "BREAK",
          "CONTINUE"
        )
        cpg.controlStructure.controlStructureType("FOR").condition.code.l shouldBe List("i < 2")
        cpg.call.codeExact("i++").cfgNext.code.l should contain("i")
        cpg.jumpTarget.name.toSet.exists(_.startsWith("case")) shouldBe true
        cpg.unknown.size shouldBe 0
        assertFlow(cpg, true)
      }
    }
    "retain throws, catches and finally cleanup" in {
      fixture(
        "String relay(String value) => value;",
        """void main(List<String> args) {
        | final input = args[0];
        | try { sink(input); throw input; } on Object catch (error, stack) { sink(error.toString()); } finally { sink('done'); }
        |}""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.controlStructure.controlStructureType.toSet should contain allOf ("TRY", "THROW")
        cpg.local.name.toSet should contain allOf ("error", "stack")
        cpg.identifier.nameExact("error").refsTo.name.toSet shouldBe Set("error")
        cpg.unknown.size shouldBe 0
      }
    }
    "short circuit boolean and null-aware expressions without repeating receivers" in {
      fixture(
        "String relay(String value) => value;",
        """String? maybe() => 'value';
        |void main(List<String> args) {
        | final input = args[0];
        | final a = args.isNotEmpty && relay(input).isNotEmpty;
        | final b = args.isEmpty || relay('constant').isNotEmpty;
        | sink(maybe() ?? input);
        | maybe()?.substring(relay(input).length);
        |}""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.call.nameExact("maybe").size shouldBe 2
        cpg.call.nameExact("<operator>.conditional").size shouldBe 2
        cpg.call.nameExact("<operator>.logicalAnd").argument(1).cfgNext.size should be >= 1
        cpg.call.nameExact("substring").argument(0).code.head should startWith("<tmp>")
        cpg.unknown.size shouldBe 0
      }
    }
    "retain cascades, interpolation and collection dependencies" in {
      fixture(
        "class Box { String value = ''; void setValue(String x) { value = x; } }",
        """void main(List<String> args) {
        | final input = args[0];
        | final box = Box()..value = input..setValue(input);
        | final values = [input, 'constant']; final mapping = {'key': input}; final unique = {input};
        | sink('value: $input');
        |}""".stripMargin
      ) { (cpg, _) =>
        cpg.call.nameExact("<init>").codeExact("Box()").size shouldBe 1
        cpg.call.nameExact("<operator>.arrayInitializer").size shouldBe 3
        cpg.call.nameExact("<operator>.formatString").argument.code.toSet should contain("input")
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty shouldBe true
      }
    }
    "represent closure captures, nested functions and tear-offs" in {
      fixture(
        "String relay(String value) => value;",
        """void main(List<String> args) {
        | final input = args[0];
        | final callback = () => input;
        | String nested() => input;
        | final forward = relay;
        | sink(callback()); sink(nested()); sink(forward('constant'));
        |}""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.methodRef.size should be >= 3
        cpg.closureBinding.size shouldBe 2
        cpg.local.closureBindingId.l.size shouldBe 2
        cpg.method.nameExact("nested").isExternal(false).size shouldBe 1
        cpg.unknown.size shouldBe 0
      }
    }
    "resolve library privacy, parts, re-exports and import combinators" in {
      fixture(
        """library helper;
        |import 'export.dart' as source show relay;
        |part 'private.dart';
        |String relay(String value) => source.relay(_private(value));
        |""".stripMargin,
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }",
        extraFiles = Map(
          "lib/private.dart" -> "part of 'helper.dart'; String _private(String value) => value;",
          "lib/export.dart"  -> "export 'implementation.dart' hide unrelated;",
          "lib/implementation.dart" -> "String relay(String value) => value; String unrelated(String value) => 'constant';"
        )
      ) { (cpg, _) =>
        cpg.namespaceBlock.fullName.toSet should contain("lib/helper.dart:<global>")
        cpg.imports.code.toSet should contain allOf (
          "import 'export.dart' as source show relay;",
          "part 'private.dart';",
          "part of 'helper.dart';",
          "export 'implementation.dart' hide unrelated;"
        )
        cpg.call.codeExact("source.relay(_private(value))").callee.filename.l shouldBe List("lib/implementation.dart")
        cpg.call.nameExact("_private").callee.filename.l shouldBe List("lib/private.dart")
        assertFlow(cpg, true)
      }
      fixture("String _private(String value) => value;", "void main() { _private('constant'); }", dataflow = false) {
        (cpg, _) =>
          cpg.call.nameExact("_private").methodFullName.l shouldBe List("<unresolved>._private")
      }
    }
    "propagate captures and function-value arguments without mixing unrelated values" in {
      for (value <- Seq("input", "'constant'")) {
        fixture(
          "String relay(String value) => value;",
          s"void main(List<String> args) { final input = args[0]; final callback = () => $value; sink(callback()); }"
        ) { (cpg, _) =>
          cpg.call.nameExact("callback").callee.isExternal.l shouldBe List(false)
          cpg.call
            .nameExact("sink")
            .argument
            .reachableByFlows(cpg.method.nameExact("main").ast.isIdentifier.nameExact("input"))
            .nonEmpty shouldBe (value == "input")
        }
        fixture(
          "String relay(String value) => value;",
          s"void main(List<String> args) { final input = args[0]; final callback = relay; sink(callback($value)); }"
        ) { (cpg, _) =>
          assertFlow(cpg, value == "input")
        }
      }
    }
    "preserve field initializers, implicit constructors and accessor calls" in {
      fixture(
        """String global = 'global';
        |class Box {
        | String value = 'initial';
        | String get read => value;
        | set write(String x) { value = x; }
        |}""".stripMargin,
        "void main() { final box = Box(); box.write = 'updated'; sink(box.read); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.method.nameExact("<clinit>").literal.code.l should contain("'global'")
        cpg.method.nameExact("<init>").isExternal(false).literal.code.l should contain("'initial'")
        cpg.call.nameExact("write").callee.isExternal.l shouldBe List(false)
        cpg.call.nameExact("write").argument(1).code.l shouldBe List("'updated'")
        cpg.call.nameExact("read").callee.isExternal.l shouldBe List(false)
      }
    }
    "exclude overwritten branch values from dataflow" in {
      fixture(
        "String relay(String value) => value;",
        "void main(List<String> args) { final input = args[0]; var value = input; if (args.isEmpty) { value = 'a'; } else { value = 'b'; } sink(relay(value)); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
      }
    }
    "bind instance tear-offs to a receiver evaluated once" in {
      for (value <- Seq("input", "'constant'")) {
        fixture(
          "class Box { String relay(String value) => value; }",
          s"void main(List<String> args) { final input = args[0]; final callback = Box().relay; sink(callback($value)); }"
        ) { (cpg, _) =>
          cpg.call.nameExact("callback").callee.name.l shouldBe List("<bound>")
          cpg.call.nameExact("<init>").codeExact("Box()").size shouldBe 1
          cpg.closureBinding.size shouldBe 1
          assertFlow(cpg, value == "input")
        }
      }
    }
    "keep anonymous functions and same-named library types distinct" in {
      fixture(
        "class Box {}",
        """void main() {
        | final a = () => 'a'; final b = () => 'b';
        | sink(a()); sink(b());
        |}""".stripMargin,
        dataflow = false,
        extraFiles = Map("lib/other.dart" -> "class Box {}")
      ) { (cpg, _) =>
        cpg.typeDecl.nameExact("Box").fullName.toSet.size shouldBe 2
        cpg.call
          .nameExact("a")
          .callee
          .fullName
          .toSet
          .intersect(cpg.call.nameExact("b").callee.fullName.toSet) shouldBe empty
        cpg.method.nameExact("<lambda>").size shouldBe 2
      }
    }
    "forward super formals and reordered factory parameters" in {
      fixture(
        """class Base { Base(String value); }
        |class Box extends Base {
        | Box(super.value);
        | Box.named({required String first, required String second}) : super(first);
        | factory Box.make({required String second, required String first}) = Box.named;
        |}""".stripMargin,
        "void main() { Box('x'); Box.make(first: 'a', second: 'b'); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.method.nameExact("<init>").call.codeExact("super()").argument(1).code.l should contain("value")
        val redirected = cpg.method.nameExact("make").call.nameExact("named").head
        redirected.argument.code.l should contain allOf ("second", "first")
        redirected.argument(1).code shouldBe "first"
        redirected.argument(2).code shouldBe "second"
        cpg.call.codeExact("Box.make(first: 'a', second: 'b')").argument.argumentIndex.toSet shouldBe Set(1, 2)
      }
    }
    "evaluate null-assignment locations once and retain operator dependencies" in {
      fixture(
        "List<String?> values() => [null]; int index() => 0;",
        """void main(List<String> args) {
        | final input = args[0]; values()[index()] ??= input;
        | var count = 0; count += 1; count++; --count;
        | final ok = (input is String) && !(count < 0);
        | sink((input as String));
        |}""".stripMargin
      ) { (cpg, _) =>
        cpg.call.nameExact("values").size shouldBe 1
        cpg.call.nameExact("index").size shouldBe 1
        cpg.call.nameExact("<operator>.conditional").size shouldBe 1
        cpg.call.name.toSet should contain allOf (
          "<operator>.addition",
          "<operator>.postIncrement",
          "<operator>.preDecrement",
          "<operator>.cast",
          "<operator>.instanceOf"
        )
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty shouldBe true
      }
    }
    "track field writes and exclude unrelated field values" in {
      for (value <- Seq("input", "'constant'")) {
        fixture(
          "class Box { String value = ''; }",
          s"void main(List<String> args) { final input = args[0]; final box = Box(); box.value = $value; sink(box.value); }"
        ) { (cpg, _) =>
          cpg.call
            .nameExact("sink")
            .argument
            .reachableByFlows(cpg.identifier.nameExact("input"))
            .nonEmpty shouldBe (value == "input")
        }
      }
    }
    "guard complete null-aware chains and index arguments" in {
      fixture(
        "String? maybe() => null; int index() => 0;",
        """void main() {
        | maybe()?.substring(index()).toUpperCase();
        | List<String>? values;
        | sink(values?[index()] ?? 'constant');
        |}""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.call.nameExact("maybe").size shouldBe 1
        val guarded = cpg.call.nameExact("<operator>.conditional").filter(_.code.contains("toUpperCase")).head
        guarded.argument(2).ast.isCall.name.toSet should contain allOf ("substring", "toUpperCase", "index")
        guarded.argument(3).code shouldBe "null"
        cpg.call.nameExact("index").size shouldBe 2
        cpg.unknown.size shouldBe 0
      }
    }
    "retain generic function references and constructor tear-offs" in {
      fixture(
        """T identity<T>(T value) => value;
        |class Box { Box(String value); }
        |String apply(String callback(String value), String value) => callback(value);
        |""".stripMargin,
        "void main() { final create = Box.new; final echo = identity<String>; create(echo('value')); apply(echo, 'other'); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.call.nameExact("create").callee.name.l shouldBe List("<init>")
        cpg.call.nameExact("create").argument(0).isCall.name.l shouldBe List("<operator>.alloc")
        cpg.call.nameExact("echo").callee.name.l shouldBe List("identity")
        cpg.method.nameExact("apply").parameter.name.toSet should contain("callback")
        cpg.unknown.size shouldBe 0
      }
    }
    "bind reordered named arguments through function values" in {
      for (value <- Seq("input", "'constant'")) {
        fixture(
          "String relay({required String value, required String ignored}) => value;",
          s"void main(List<String> args) { final input = args[0]; final callback = relay; sink(callback(ignored: input, value: $value)); }"
        ) { (cpg, _) =>
          cpg.call.nameExact("callback").argument.argumentIndex.toSet shouldBe Set(0, 1, 2)
          cpg.call.nameExact("callback").argument(1).code.l shouldBe List(value)
          assertFlow(cpg, value == "input")
        }
      }
    }
    "preserve receiver evaluation for unresolved dynamic properties" in {
      fixture("dynamic receiver() => Object();", "void main() { sink(receiver().missing); }", dataflow = false) {
        (cpg, _) =>
          cpg.call.nameExact("receiver").size shouldBe 1
          cpg.call.nameExact("<operator>.fieldAccess").argument(2).code.l shouldBe List("missing")
      }
    }
    "report unhandled modern syntax explicitly" in {
      fixture("String relay(String value) => value;", "void main() { final record = (1, 2); }", dataflow = false) {
        (cpg, _) =>
          cpg.unknown.parserTypeName.l should contain("RecordLiteral")
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
