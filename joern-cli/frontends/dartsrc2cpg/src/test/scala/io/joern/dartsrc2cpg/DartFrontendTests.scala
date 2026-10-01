package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.FullNameSemanticsParser
import io.joern.dataflowengineoss.queryengine.{EngineConfig, EngineContext, QueryDiagnostics}
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.{Cpg, Operators}
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
    .iterate(Paths.get(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
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
    extraFiles: Map[String, String] = Map.empty,
    environment: String = "analyzer-default"
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
        .createCpg(
          config
            .copy(environment = environment)
            .withInputPath(dir.toString)
            .withOutputPath(dir.resolve("cpg.bin").toString)
        )
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
    "resolve mixin super members from preceding application implementations" in {
      val source  = Files.readString(frontend.resolve("src/test/resources/semantics/mixin_super.dart"))
      val splitAt = source.indexOf("class Direct")
      for (split <- Seq(false, true))
        fixture(
          if (split) source.take(splitAt) else source,
          "void main() {}",
          extraFiles =
            if (split) Map("lib/applications.dart" -> ("import 'helper.dart';\n" + source.drop(splitAt))) else Map.empty
        ) { (cpg, _) =>
          val owners = Set("Base", "Prefix", "Prior").map(name => cpg.typeDecl.nameExact(name).head.fullName)
          val probe  = cpg.typeDecl.nameExact("Probe").head
          for (
            (name, member) <- Seq(
              "invoke"   -> "echo",
              "getter"   -> "read",
              "setter"   -> "write",
              "plus"     -> "+",
              "index"    -> "[]",
              "indexSet" -> "[]="
            )
          ) {
            val call = probe.method.nameExact(name).call.nameExact(member).head
            withClue(name) { call.callee.astParentFullName.toSet shouldBe owners }
          }
          val bound = probe.method.nameExact("bound").call.nameExact("callback").callee.nameExact("<bound>").head
          bound.call.nameExact("echo").callee.astParentFullName.toSet shouldBe owners
          for (name <- Seq("invoke", "bound")) {
            val method = probe.method.nameExact(name).head
            for (source <- Seq("input", "ignored")) {
              withClue(s"$name $source") {
                method.ast.isReturn
                  .reachableByFlows(method.parameter.nameExact(source))
                  .nonEmpty shouldBe (source == "input")
              }
            }
          }
        }
    }
    "pin super and extension dispatch while retaining virtual calls" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/static_dispatch.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val base = cpg.typeDecl.nameExact("Base").head.fullName
        for (
          (name, member) <- Seq(
            "directSuper"   -> "echo",
            "getterSuper"   -> "read",
            "setterSuper"   -> "write",
            "operatorSuper" -> "+",
            "indexSuper"    -> "[]",
            "indexSetSuper" -> "[]="
          )
        ) {
          val method = cpg.method.nameExact(name).head
          val call   = method.ast.isCall.nameExact(member).head
          withClue(name) {
            call.dispatchType shouldBe io.shiftleft.codepropertygraph.generated.DispatchTypes.STATIC_DISPATCH
            call.callee.astParentFullName.toSet shouldBe Set(base)
            method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe true
          }
        }
        for (name <- Seq("tearoffSuper", "invokeSuper", "extensionTearoff", "extensionInvoke")) {
          val method     = cpg.method.nameExact(name).head
          val invocation = method.ast.isCall.filter(call => call.name == "callback" || call.name == "<invoke>").head
          val bound      = invocation.callee.nameExact("<bound>").head
          val call       = bound.ast.isCall.nameExact("echo").head
          call.dispatchType shouldBe io.shiftleft.codepropertygraph.generated.DispatchTypes.STATIC_DISPATCH
          call.callee.astParentFullName.toSet shouldBe Set(
            if (name.contains("Super")) base else cpg.typeDecl.nameExact("View").head.fullName
          )
        }
        for (
          name <- Seq(
            "directSuper",
            "tearoffSuper",
            "invokeSuper",
            "staticTearoff",
            "extensionTearoff",
            "extensionInvoke"
          )
        ) {
          val method = cpg.method.nameExact(name).head
          for (source <- Seq("input", "ignored")) {
            withClue(s"$name $source") {
              method.ast.isReturn
                .reachableByFlows(method.parameter.nameExact(source))
                .nonEmpty shouldBe (source == "input")
            }
          }
        }
        cpg.method
          .nameExact("staticTearoff")
          .head
          .ast
          .isCall
          .nameExact("callback")
          .callee
          .astParentFullName
          .toSet shouldBe Set(base)
        cpg.method.nameExact("extensionSetter").head.ast.isCall.nameExact("write").dispatchType.l shouldBe
          List(io.shiftleft.codepropertygraph.generated.DispatchTypes.STATIC_DISPATCH)
        val virtual = cpg.method.nameExact("virtual").head.ast.isCall.nameExact("echo").head
        virtual.dispatchType shouldBe io.shiftleft.codepropertygraph.generated.DispatchTypes.DYNAMIC_DISPATCH
        virtual.callee.astParentFullName.toSet shouldBe Set(base, cpg.typeDecl.nameExact("Derived").head.fullName)
      }
    }
    "retain instantiated generic tear-off types, source and stable aliases" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/generic_tearoffs.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for (
          (code, typ) <- Seq(
            "forward<String>"              -> "String Function(String, String)",
            "forward<int>"                 -> "int Function(int, int)",
            "Receiver.select<String>"      -> "String Function(String, String)",
            "obtain('bound').echo<String>" -> "String Function(String, String)"
          )
        ) {
          val references = cpg.methodRef.codeExact(code).l
          references should not be empty
          references.map(_.typeFullName).toSet shouldBe Set(typ)
        }
        for (
          name <- Seq(
            "top",
            "integers",
            "staticMethod",
            "aliases",
            "namedAliases",
            "defaultAliases",
            "bound",
            "boundAliases"
          )
        ) {
          val method   = cpg.method.nameExact(name).head
          val callback = method.ast.isCall.nameExact("callback").head
          callback.callee.isExternal(false).size shouldBe 1
          for (source <- Seq("input", "ignored")) {
            withClue(s"$name $source") {
              method.ast.isReturn
                .reachableByFlows(method.parameter.nameExact(source))
                .nonEmpty shouldBe (source == "input")
            }
          }
        }
        for (name <- Seq("aliases", "boundAliases")) {
          val method       = cpg.method.nameExact(name).head
          val instantiated = method.ast.isIdentifier.codeExact("generic<String>").head
          instantiated.typeFullName shouldBe "String Function(String, String)"
          instantiated.refsTo.collect { case local: io.shiftleft.codepropertygraph.generated.nodes.Local =>
            local.name
          }.toList shouldBe List("generic")
        }
        for (name <- Seq("bound", "boundAliases")) {
          val method = cpg.method.nameExact(name).head
          method.ast.isCall.nameExact("obtain").size shouldBe 1
          method.ast.isMethodRef.flatMap(_._captureOut).size shouldBe 1
          method.ast.isCall.nameExact("callback").callee.name.l shouldBe List("<bound>")
        }
        val named = cpg.method.nameExact("namedAliases").head.ast.isCall.nameExact("callback").head
        named.argument(1).code shouldBe "input"
        named.argument(1).order shouldBe 3
        named.argument(2).code shouldBe "ignored"
        named.argument(2).order shouldBe 2
        named.receiver.order.l shouldBe List(1)
        val defaulted = cpg.method.nameExact("defaultAliases").head.ast.isCall.nameExact("callback").head
        defaulted.argument(2).code shouldBe "null"
      }
    }
    "avoid choosing a nested tear-off as an unknown function value's target" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/generic_tearoffs.dart")),
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        for (name <- Seq("mutable", "conditional", "returned")) {
          val callback = cpg.method.nameExact(name).head.ast.isCall.nameExact("callback").head
          withClue(name) { callback.callee.isExternal(false).size shouldBe 0 }
        }
      }
    }
    "keep inline generic function scopes distinct within aliases and methods" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/generic_function_scopes.dart")),
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        val signatures = cpg.typeDecl.nameExact("<functionType>").l
        signatures should have size 7
        signatures.map(_.fullName).distinct should have size 7
        val parameters = signatures.flatMap(_.astChildren.isTypeDecl.nameExact("T").l)
        parameters should have size 7
        parameters.map(_.fullName).distinct should have size 7
        parameters.map(_.inheritsFromTypeFullName.head).count(_.endsWith(":CLASS:Data")) shouldBe 5
        parameters.map(_.inheritsFromTypeFullName.head).count(_.endsWith(":CLASS:num")) shouldBe 2
        cpg.typeDecl.nameExact("Poly").astChildren.isTypeDecl.nameExact("<functionType>").size shouldBe 1
        cpg.typeDecl.nameExact("Both").astChildren.isTypeDecl.nameExact("<functionType>").size shouldBe 2
        cpg.method.nameExact("callbacks").astChildren.isTypeDecl.nameExact("<functionType>").size shouldBe 2
        cpg.method.nameExact("callback").astChildren.isTypeDecl.nameExact("<functionType>").size shouldBe 1
        cpg.typeDecl
          .nameExact("Higher")
          .astChildren
          .isTypeDecl
          .nameExact("F")
          .astChildren
          .isTypeDecl
          .nameExact("<functionType>")
          .size shouldBe 1
      }
    }
    "preserve generic parameter scopes and bound relationships" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/generic_scopes.dart")),
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        val data    = cpg.typeDecl.nameExact("Data").head
        val box     = cpg.typeDecl.nameExact("Box").head
        val other   = cpg.typeDecl.nameExact("Other").head
        val boxed   = box.astChildren.isTypeDecl.nameExact("T").head
        val numeric = other.astChildren.isTypeDecl.nameExact("T").head
        boxed.fullName should not be numeric.fullName
        boxed.inheritsFromTypeFullName shouldBe Seq(data.fullName)
        numeric.inheritsFromTypeFullName.head should endWith(":CLASS:num")
        box.member.nameExact("value").typeFullName.l shouldBe List(boxed.fullName)
        val echo = box.method.nameExact("echo").head
        echo.parameter.nameExact("input").typeFullName.l shouldBe List(boxed.fullName)
        echo.parameter.nameExact("input").typ.referencedTypeDecl.fullName.l shouldBe List(boxed.fullName)
        echo.methodReturn.typeFullName shouldBe boxed.fullName
        echo.ast.isIdentifier.nameExact("input").typeFullName.toSet shouldBe Set(boxed.fullName)
        val narrower = box.method.nameExact("narrower").head
        val narrow   = narrower.astChildren.isTypeDecl.nameExact("U").head
        narrow.inheritsFromTypeFullName shouldBe Seq(boxed.fullName)
        narrower.parameter.nameExact("input").typeFullName.l shouldBe List(narrow.fullName)
        for (name <- Seq("first", "second", "nested", "helper")) {
          val method    = cpg.method.nameExact(name).head
          val parameter = method.astChildren.isTypeDecl.nameExact("T").head
          method.parameter.index(1).typeFullName.l shouldBe List(parameter.fullName)
        }
        val genericNames = cpg.typeDecl.fullName.l.filter(_.contains(":TYPE_PARAMETER:"))
        genericNames should have size 13
        genericNames.distinct should have size 13
        for (name <- Seq("Converter", "Legacy", "Pair")) {
          val alias = cpg.typeDecl.nameExact(name).head
          alias.astChildren.isTypeDecl.size shouldBe (if (name == "Pair") 2 else 1)
        }
        val pair = cpg.typeDecl.nameExact("Pair").head
        pair.astChildren.isTypeDecl.nameExact("T").head.inheritsFromTypeFullName shouldBe
          Seq(pair.astChildren.isTypeDecl.nameExact("U").head.fullName)
        val unbounded = cpg.typeDecl.nameExact("Unbounded").head
        val unbound   = unbounded.astChildren.isTypeDecl.nameExact("T").head
        unbound.genericSignature shouldBe "T extends Object?"
        unbound.inheritsFromTypeFullName.head should endWith(":CLASS:Object")
        val optional = unbounded.method.nameExact("optional").head
        optional.genericSignature should include("T?")
        optional.local.nameExact("saved").genericSignature.l shouldBe List("T?")
        val recursive = cpg.typeDecl.nameExact("Recursive").head.astChildren.isTypeDecl.head
        recursive.genericSignature shouldBe "T extends Comparable<T>"
        recursive.inheritsFromTypeFullName.head should endWith(":CLASS:Comparable")
        val read = box.method.nameExact("read").head.ast.isCall.nameExact("read").head
        read.callee.isExternal(false).fullName.l shouldBe data.method.nameExact("read").fullName.l
      }
    }
    "separate exception payloads from normal values across method calls" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/exception_calls.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for (name <- Seq("catchForward", "catchRethrow")) {
          val method = cpg.method.nameExact(name).head
          for (source <- Seq("input", "normal")) {
            val paths = method.ast.isReturn
              .codeExact("return error;")
              .reachableByFlows(method.parameter.nameExact(source))
              .l
            withClue(s"$name catch $source") { paths.nonEmpty shouldBe (source == "input") }
            val normal = method.ast.isReturn
              .codeNot("return error;")
              .reachableByFlows(method.parameter.nameExact(source))
              .l
            withClue(s"$name return $source") { normal.nonEmpty shouldBe (source == "normal") }
          }
        }
        for (
          (name, expected) <- Seq(
            "catchConstructed" -> true,
            "catchSuppressed"  -> false,
            "catchHandled"     -> false,
            "independentCalls" -> false,
            "catchStack"       -> false
          )
        ) {
          val method = cpg.method.nameExact(name).head
          withClue(name) {
            method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe expected
          }
        }
      }
    }
    "retain ordered catch filters and bind caught values explicitly" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/catch_dispatch.dart")),
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        val method  = cpg.method.nameExact("choose").head
        val filters = method.call.nameExact("<operator>.instanceOf").l.sortBy(_.order)
        filters.flatMap(_.argument.isTypeRef.code).toSet shouldBe Set("FormatException", "StateError")
        val first       = filters.find(_.argument.isTypeRef.codeExact("FormatException").nonEmpty).get
        val second      = filters.find(_.argument.isTypeRef.codeExact("StateError").nonEmpty).get
        val firstBranch = first.astParent.asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.ControlStructure]
        firstBranch._falseBodyOut
          .cast[io.shiftleft.codepropertygraph.generated.nodes.AstNode]
          .ast
          .isCall
          .toSet should contain(second)
        firstBranch._trueBodyOut
          .cast[io.shiftleft.codepropertygraph.generated.nodes.AstNode]
          .ast
          .isCall
          .toSet should not contain second
        first._cfgOut.toSet should contain(second.argument(1))
        first.argument.isTypeRef.typeFullName.head should include("FormatException")
        method.controlStructure.controlStructureType("CATCH").size shouldBe 1
        method.call.nameExact("<operator>.caughtException").size shouldBe 1
        method.call.nameExact("<operator>.caughtStackTrace").size shouldBe 1
        method.local.nameExact("error").size shouldBe 3
        method.call.nameExact("<operator>.assignment").argument(1).isIdentifier.nameExact("error").size shouldBe 3
        cpg.method.nameExact("nested").controlStructure.codeExact("rethrow").flatMap(_._argumentOut).size shouldBe 2
        val unmatched = cpg.method.nameExact("filtered").controlStructure.codeExact("<unmatched catch: rethrow>").head
        unmatched._argumentOut.size shouldBe 2
        method.local.nameExact("error").foreach { local =>
          local._refIn.cast[io.shiftleft.codepropertygraph.generated.nodes.Identifier].size should be >= 2
        }
        cpg.unknown.size shouldBe 0
      }
    }
    "carry thrown values through catches and cleanup without mixing channels" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/catch_dispatch.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for (
          (name, expected) <- Seq(
            "caughtValue"            -> true,
            "caughtConstructed"      -> true,
            "forwardedRethrow"       -> true,
            "throughFinally"         -> true,
            "throughHandledCleanup"  -> true,
            "localBreakInCleanup"    -> true,
            "localContinueInCleanup" -> true,
            "caughtTrace"            -> false,
            "caughtReplacement"      -> false,
            "independentHandler"     -> false,
            "replacedInCleanup"      -> false,
            "returnedFromCleanup"    -> false,
            "breakFromCleanup"       -> false,
            "continueFromCleanup"    -> false
          )
        ) {
          val method = cpg.method.nameExact(name).head
          withClue(name) {
            method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe expected
          }
        }
        val handled = cpg.method.nameExact("throughHandledCleanup").head
        handled.call
          .nameExact("toString")
          .argument(0)
          .reachableByFlows(handled.parameter.nameExact("input"))
          .isEmpty shouldBe true
      }
    }
    "preserve pending return flow when cleanup handles its own failure" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/catch_dispatch.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val method = cpg.method.nameExact("preservedReturn").head
        val paths  = Iterator.single(method.methodReturn).reachableByFlows(method.parameter.nameExact("input")).l
        paths.nonEmpty shouldBe true
        method.ast.isReturn.codeExact("return input;").head._reachingDefOut.toSet should contain(method.methodReturn)
      }
    }
    "represent generated enum storage and concrete enum accessors" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/enum_members.dart")),
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        for (name <- Seq("Simple", "Enhanced", "Custom", "Shadow")) {
          val declaration = cpg.typeDecl.nameExact(name).isExternal(false).head
          declaration.member.name.toSet should contain allOf ("values", "index", "<enumName>")
          val initializer = declaration.method.nameExact("<clinit>").head
          val values      =
            initializer.call.nameExact("<operator>.assignment").filter(_.argument(1).code.endsWith(".values")).l
          values.size shouldBe 1
          Iterator.single(values.head.argument(2)).isCall.name.l shouldBe List("<operator>.arrayInitializer")
          val expected = name match {
            case "Simple" | "Enhanced" => "const [first, second]"
            case _                     => "const [first]"
          }
          values.head.argument(2).code shouldBe expected
          declaration.member
            .nameExact("values", "index", "<enumName>")
            .modifier
            .modifierType
            .count(_ == "FINAL") shouldBe 3
          val writes =
            initializer.call.nameExact("<operator>.assignment").filter(_.argument(1).code.endsWith(".index")).l
          writes.map(_.argument(2).code).toSet shouldBe (if (name == "Simple" || name == "Enhanced") Set("0", "1")
                                                         else Set("0"))
          writes.map(_.code).distinct.size shouldBe writes.size
        }
        val describe = cpg.method.nameExact("describe").head
        describe.call.nameExact("index", "name", "toString").callee.isExternal(false).size shouldBe 3
        cpg.method
          .nameExact("overridden")
          .call
          .nameExact("toString")
          .callee
          .isExternal(false)
          .ast
          .isLiteral
          .code
          .l should contain("'custom'")
        cpg.method
          .nameExact("named")
          .call
          .nameExact("name")
          .callee
          .isExternal(false)
          .ast
          .isLiteral
          .code
          .l should contain("'shadow'")
        cpg.method.nameExact("originalName").call.nameExact("name").callee.name.l shouldBe List("<enum:name>")
        cpg.method.nameExact("original").call.nameExact("toString").callee.name.l shouldBe List("<enum:toString>")
        cpg.method.nameExact("<bound>").call.nameExact("toString").callee.name.l shouldBe List("<enum:toString>")
        cpg.unknown.size shouldBe 0
      }
    }
    "preserve VM and web interpolation order across adjacent and nested literals" in {
      for (environment <- Seq("analyzer-default", "vm", "web"))
        fixture(
          Files.readString(frontend.resolve("src/test/resources/semantics/interpolation_order.dart")),
          "void main() {}",
          dataflow = false,
          environment = environment
        ) { (cpg, _) =>
          for (name <- Seq("interpolate", "adjacent", "nested", "boundary")) {
            val method  = cpg.method.nameExact(name).head
            val first   = method.call.nameExact("value").codeExact("value(1)").head
            val visited = scala.collection.mutable.Set.empty[Long]
            def callsAfter(node: io.shiftleft.codepropertygraph.generated.nodes.CfgNode): List[String] = {
              if (!visited.add(node.id)) Nil
              else {
                val current = node match {
                  case call: io.shiftleft.codepropertygraph.generated.nodes.Call
                      if Set("value", "toString")(call.name) =>
                    List(call.name)
                  case _ => Nil
                }
                current ++ node.cfgNext.l.flatMap(callsAfter)
              }
            }
            val expected =
              if (name == "boundary" || environment == "web") List("value", "toString", "value", "toString")
              else List("value", "value", "toString", "toString")
            withClue(name) { callsAfter(first) shouldBe expected }
            method.call.nameExact("value").size shouldBe 2
            method.call.nameExact("toString").size shouldBe 2
          }
          val guards = cpg.method.nameExact("nullable").call.nameExact("<operator>.conditional").l
          guards.size shouldBe 2
          guards.foreach { guard =>
            guard.argument(3).code shouldBe "\"null\""
            guard.argument(1).cfgNext.l should contain(guard.argument(3))
          }
        }
    }
    "preserve interpolation results without writing independent fragments" in {
      fixture(
        """void observe(String value) {}
          |String assemble(String input, String independent) {
          | final result = '$input:$independent';
          | observe(independent);
          | return result;
          |}
          |""".stripMargin,
        "void main() {}"
      ) { (cpg, _) =>
        val method = cpg.method.nameExact("assemble").head
        cpg.call
          .nameExact("observe")
          .argument(1)
          .reachableByFlows(method.parameter.nameExact("input"))
          .isEmpty shouldBe true
        for (name <- Seq("input", "independent")) {
          method.ast.isReturn.reachableByFlows(method.parameter.nameExact(name)).nonEmpty shouldBe true
        }
      }
    }
    "retain implicit user string conversion calls in interpolation" in {
      fixture(
        """void observe(String value) {}
          |class Render {
          | final String value;
          | Render(this.value);
          | @override String toString() { observe(value); return value; }
          |}
          |String render(Render input) => '$input';
          |String bounded<T extends Render>(T input) => '$input';
          |extension type Wrapped(Render value) {}
          |String erased(Wrapped input) => '$input';
          |String unknown(dynamic input) => '$input';
          |""".stripMargin,
        "void main() {}"
      ) { (cpg, _) =>
        val conversion = cpg.method.nameExact("render").call.nameExact("toString").head
        conversion.callee.isExternal(false).name.l shouldBe List("toString")
        conversion.receiver.argumentIndex.l shouldBe List(0)
        for (name <- Seq("bounded", "erased")) {
          cpg.method.nameExact(name).call.nameExact("toString").callee.fullName.l shouldBe conversion.callee.fullName.l
        }
        cpg.call
          .nameExact("observe")
          .argument(1)
          .reachableByFlows(cpg.method.nameExact("render").parameter.nameExact("input"))
          .nonEmpty shouldBe true
        cpg.method.nameExact("unknown").call.nameExact("toString").methodFullName.l shouldBe List(
          "<unresolved>.toString"
        )
      }
    }
    "expose callback-result feedback into an indexed mapping receiver" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/callback_witness.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val method = cpg.method.nameExact("decorate").head
        val source = method.parameter.nameExact("value").head
        method.ast.isReturn.reachableByFlows(Iterator.single(source)).nonEmpty shouldBe true
        // Query the suspicious endpoint directly: longest return witnesses can choose another route.
        val feedback = cpg.method.parameter.nameExact("index").reachableByFlows(Iterator.single(source)).l
        feedback.exists(_.elements.exists {
          case _: io.shiftleft.codepropertygraph.generated.nodes.MethodRef => true
          case _                                                           => false
        }) shouldBe true
      }
    }
    "retain list callback flow with statically selected superclass targets" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/field_witness.dart")), "void main() {}") {
        (cpg, _) =>
          val method = cpg.method.nameExact("writeList").head
          val source = method.parameter.nameExact("items").head
          val paths  = method.call.nameExact("callback").argument(1).reachableByFlows(Iterator.single(source)).l
          paths.nonEmpty shouldBe true
          for ((owner, target) <- Seq("Fields" -> "Base", "Derived" -> "Fields")) {
            val call =
              cpg.typeDecl.nameExact(owner).method.nameExact("write").call.codeExact("super.write(writer)").head
            call.dispatchType shouldBe io.shiftleft.codepropertygraph.generated.DispatchTypes.STATIC_DISPATCH
            call.callee.astParentFullName.toSet shouldBe Set(cpg.typeDecl.nameExact(target).head.fullName)
          }
      }
    }
    "expose read-only parameter output detours without mixing separate calls" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/caller_forwarding.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val relay = cpg.method.nameExact("relay").head
        val paths = relay.ast.isReturn.reachableByFlows(relay.parameter.nameExact("value")).l
        paths.nonEmpty shouldBe true
        paths.exists(_.elements.exists {
          case call: io.shiftleft.codepropertygraph.generated.nodes.Call => call.code == "produce(value)"
          case _                                                         => false
        }) shouldBe true
        val semantics = DefaultSemantics().plus(
          List(io.joern.dataflowengineoss.semanticsloader.FlowSemantic.from(relay.fullName, List((1, 1), (1, -1))))
        )
        semantics.initialize(cpg)
        val modeled = EngineContext(semantics = semantics)
        val direct  = relay.ast.isReturn.reachableByFlows(relay.parameter.nameExact("value"))(modeled).l
        direct.nonEmpty shouldBe true
        direct.flatMap(_.elements).forall {
          case node: io.shiftleft.codepropertygraph.generated.nodes.CfgNode => node.method.name != "caller"
          case _                                                            => true
        } shouldBe true
        for (engine <- Seq(context, modeled); name <- Seq("caller", "separate")) {
          val method = cpg.method.nameExact(name).head
          method.ast.isReturn
            .reachableByFlows(method.parameter.filter(_.index == 1))(engine)
            .nonEmpty shouldBe (name == "caller")
        }
      }
    }
    "type generated initialization and pattern guards as boolean values" in {
      fixture(
        """late final String text = 'value';
          |String? select(Object value, String? optional) {
          | if (value case [String first]) { return optional ?? first; }
          | return text;
          |}
          |""".stripMargin,
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        val predicates = cpg.call
          .nameExact(
            "<operator>.isInitialized",
            "<operator>.patternShape",
            "<operator>.instanceOf",
            "<operator>.logicalNot",
            "<operator>.equals",
            "<operator>.notEquals",
            "<operator>.logicalAnd"
          )
          .l
        predicates.map(_.name).toSet should contain allOf (
          "<operator>.isInitialized",
          "<operator>.instanceOf",
          "<operator>.equals",
          "<operator>.notEquals"
        )
        predicates.foreach(call =>
          withClue(call.name) {
            (call.typeFullName == "bool" || call.typeFullName.endsWith(":CLASS:bool")) shouldBe true
          }
        )
      }
    }
    "retain unresolved operator dispatch and conservative effects for dynamic receivers" in {
      fixture(
        """dynamic unknown(dynamic receiver, int index, dynamic value) {
          | receiver + value; receiver < value; receiver == value; receiver != value;
          | receiver[index] = value; receiver[index] += value; ++receiver;
          | return receiver[index];
          |}
          |dynamic effect(dynamic receiver, dynamic independent) { receiver + independent; return independent; }
          |bool objectEquality(Object receiver, Object value) => receiver != value;
          |""".stripMargin,
        "void main() {}"
      ) { (cpg, _) =>
        val method = cpg.method.nameExact("unknown").head
        method.call.nameExact("+").size shouldBe 3
        method.call.nameExact("[]=").size shouldBe 2
        method.call.nameExact("[]").size shouldBe 2
        method.call.nameExact("==").size shouldBe 2
        method.call.nameExact("<").size shouldBe 1
        method.call.nameExact("+", "[]", "[]=", "==", "<").l.foreach { call =>
          call.dispatchType shouldBe io.shiftleft.codepropertygraph.generated.DispatchTypes.DYNAMIC_DISPATCH
          call.receiver.argumentIndex.l shouldBe List(0)
        }
        val effect = cpg.method.nameExact("effect").head
        Iterator
          .single(effect.methodReturn)
          .reachableByFlows(effect.parameter.nameExact("receiver"))
          .nonEmpty shouldBe true
        val equality = cpg.method.nameExact("objectEquality").head
        equality.call.nameExact("==").size shouldBe 1
        equality.call.nameExact("<operator>.logicalNot").size shouldBe 1
      }
    }
    "keep primitive arithmetic and bitwise operands separate from the result" in {
      for (operator <- Seq("+", "-", "*", "/", "~/", "%", "&", "|", "^", "<<", ">>", ">>>")) {
        fixture(
          s"void observe(int value) {} num calculate(int input, int independent) { final result = input $operator independent; observe(independent); return result; }",
          "void main() {}"
        ) { (cpg, _) =>
          withClue(operator) {
            cpg.call
              .nameExact("observe")
              .argument(1)
              .reachableByFlows(cpg.method.nameExact("calculate").parameter.nameExact("input"))
              .isEmpty shouldBe true
            cpg.method
              .nameExact("calculate")
              .methodReturn
              .reachableByFlows(cpg.method.nameExact("calculate").parameter.nameExact("input"))
              .nonEmpty shouldBe true
          }
        }
      }
    }
    "keep primitive comparison operands separate while preserving their boolean result" in {
      for (operator <- Seq("==", "!=", "<", ">", "<=", ">=")) {
        fixture(
          s"void observe(int value) {} bool compare(int input, int independent) { final result = input $operator independent; observe(independent); return result; }",
          "void main() {}"
        ) { (cpg, _) =>
          withClue(operator) {
            cpg.call
              .nameExact("observe")
              .argument(1)
              .reachableByFlows(cpg.method.nameExact("compare").parameter.nameExact("input"))
              .isEmpty shouldBe true
            cpg.method
              .nameExact("compare")
              .methodReturn
              .reachableByFlows(cpg.method.nameExact("compare").parameter.nameExact("input"))
              .nonEmpty shouldBe true
          }
        }
      }
    }
    "distinguish copied byte contents from buffer size and an independent buffer" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/byte_copy.dart")), "void main() {}") {
        (cpg, _) =>
          val rules = new FullNameSemanticsParser()
            .parseFile(frontend.resolve("dataflow/bytes.semantics").toString)
            .map(_.copy(regex = true))
          val semantics = DefaultSemantics().plus(rules)
          semantics.initialize(cpg)
          val modeled = EngineContext(semantics = semantics)
          for (
            (name, stock, expected) <- Seq(
              ("copy", true, true),
              ("allocate", true, false),
              ("separate", true, false),
              ("sourceIsolation", true, false)
            )
          ) {
            val method = cpg.method.nameExact(name).head
            withClue(name) {
              method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe stock
              method.ast.isReturn
                .reachableByFlows(method.parameter.nameExact("input"))(modeled)
                .nonEmpty shouldBe expected
            }
          }
          cpg.call.nameExact("setRange").callee.l.foreach(method => semantics.forMethod(method).isDefined shouldBe true)
          cpg.method.fullNameExact("dart:typed_data#36552:CONSTRUCTOR:new").size shouldBe 1
      }
    }
    "preserve returns through multiple catches without inventing a finally" in {
      fixture(
        "String relay(String value) { try { return value; } on FormatException { return 'format'; } catch (error) { return 'other'; } }",
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
      ) { (cpg, _) =>
        val method = cpg.method.nameExact("relay").head
        method.ast.isReturn.foreach { ret =>
          ret.out(io.shiftleft.codepropertygraph.generated.EdgeTypes.CFG).toSet shouldBe Set(method.methodReturn)
        }
        assertFlow(cpg, true)
      }
    }
    "defer ordinary static and top-level initialization until a getter is read" in {
      fixture(
        """String initialize() => 'value';
          |String global = initialize();
          |final String immutable = initialize();
          |class Lazy { static String field = initialize(); static final String fixed = initialize(); }
          |""".stripMargin,
        "void main() { global = 'assigned'; Lazy.field = 'assigned'; sink(global); sink(immutable); sink(Lazy.field); sink(Lazy.fixed); }"
      ) { (cpg, _) =>
        cpg.call.nameExact("initialize").size shouldBe 4
        cpg.method.fullName(".*:GETTER:immutable").call.nameExact("<operator>.isInitialized").size shouldBe 2
        cpg.method.fullName(".*:GETTER:global").call.nameExact("<operator>.isInitialized").size shouldBe 1
        cpg.call.nameExact("initialize").method.fullName.l.foreach(_ should include("GETTER"))
        cpg.method.nameExact("<clinit>").call.nameExact("initialize").size shouldBe 0
        val setters = cpg.method.isExternal(false).fullName(".*:SETTER:.*").l
        setters.size shouldBe 2
        setters.foreach { method =>
          method.call.nameExact("initialize", "<operator>.isInitialized").size shouldBe 0
        }
        cpg.call
          .nameExact("sink")
          .argument
          .reachableByFlows(cpg.method.nameExact("initialize").ast.isLiteral)
          .nonEmpty shouldBe true
      }
    }
    "lower the selected SDK collection and late-field cases without executable holes" in {
      val upstream = frontend.resolve("conformance/upstream")
      for (name <- Seq("null_aware_evaluation.dart", "late_field_initializers.dart")) {
        withClue(name) {
          fixture(
            Files.readString(upstream.resolve(name)),
            "void main() {}",
            dataflow = false,
            extraFiles = Map("lib/expect.dart" -> Files.readString(upstream.resolve("expect.dart")))
          ) { (cpg, _) =>
            cpg.unknown.size shouldBe 0
            cpg.method.isExternal(false).nameExact("main").size shouldBe 2
            cpg.call.nameExact("equals").callee.isExternal.toSet shouldBe Set(false)
          }
        }
      }
    }
    "preserve SDK shared-case guard captures separately from the body variable" in {
      val upstream = frontend.resolve("conformance/upstream")
      fixture(
        Files.readString(upstream.resolve("guard_capture.dart")),
        "void main() {}",
        extraFiles = Map("lib/expect.dart" -> Files.readString(upstream.resolve("expect.dart")))
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        for (name <- Seq("sharedCaseUseInBody", "sharedCaseOnlyGuards")) {
          val captured = cpg.closureBinding.refOut.collect {
            case local: io.shiftleft.codepropertygraph.generated.nodes.Local
                if local.method.name.contains(name) && local.name == "a" =>
              local.id
          }.toSet
          withClue(name) { captured.size shouldBe 3 }
        }
        val body = cpg.method.nameExact("sharedCaseUseInBody").head
        body.ast.isIdentifier.nameExact("a").refsTo.toSet.size shouldBe 4
        body.ast.isCall.nameExact("<operator>.logicalOr").size shouldBe 2
      }
    }
    "preserve dependencies under source transformations and reject constant replacements" in {
      val variants = Seq(
        ("inline", "", "String relay(String value) => value;", "relay(input)", true),
        (
          "rename",
          "",
          "String relay(String renamed) { final another = renamed; return another; }",
          "relay(input)",
          true
        ),
        (
          "extract",
          "String identity(String argument) => argument;",
          "String relay(String value) => identity(value);",
          "relay(input)",
          true
        ),
        (
          "dead helper",
          "String unused(String ignored) => 'fixed';",
          "String relay(String value) => value;",
          "relay(input)",
          true
        ),
        (
          "named order",
          "",
          "String relay({required String value, required String ignored}) => value;",
          "relay(ignored: 'fixed', value: input)",
          true
        ),
        (
          "named order reversed",
          "",
          "String relay({required String value, required String ignored}) => value;",
          "relay(value: input, ignored: 'fixed')",
          true
        ),
        ("constant", "", "String relay(String value) => 'fixed';", "relay(input)", false),
        (
          "overwrite",
          "",
          "String relay(String value) { var local = value; local = 'fixed'; return local; }",
          "relay(input)",
          false
        ),
        (
          "unrelated named",
          "",
          "String relay({required String value, required String ignored}) => value;",
          "relay(value: 'fixed', ignored: input)",
          false
        )
      )
      for ((name, helper, relay, call, expected) <- variants; split <- Seq(false, true)) {
        val source = if (split) "export 'transformed.dart';\n" + helper else helper + "\n" + relay
        val moved  = (if (name == "extract") "import 'helper.dart';\n" else "") + relay
        val files  = if (split) Map("lib/transformed.dart" -> moved) else Map.empty[String, String]
        withClue(s"$name, split=$split: ") {
          fixture(source, s"void main(List<String> args) { final input = args[0]; sink($call); }", extraFiles = files) {
            (cpg, _) =>
              cpg.call
                .nameExact("sink")
                .argument
                .reachableByFlows(cpg.identifier.nameExact("input"))
                .nonEmpty shouldBe expected
              cpg.call.nameExact("relay").callee.isExternal.l shouldBe List(false)
          }
        }
      }
    }
    "isolate calls on independent receivers with a matching positive control" in {
      fixture(
        "class Box { String echo(String value) => value; } String relay(String value) { final first = Box(); final second = Box(); first.echo(value); return second.echo('fixed'); }",
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
        cpg.call
          .codeExact("first.echo(value)")
          .argument(1)
          .reachableByFlows(cpg.identifier.nameExact("input"))
          .nonEmpty shouldBe true
        cpg.call.nameExact("echo").receiver.isIdentifier.refsTo.name.toSet shouldBe Set("first", "second")
      }
    }
    "accumulate values across nested collection loops without treating conditions as elements" in {
      for (
        (elements, expected) <- Seq(
          "for (var i = 0; i < 2; i++) for (var j = 0; j < 2; j++) value" -> true,
          "for (var i = 0; i < value.length; i++) 'constant'"             -> false,
          "if (value.isNotEmpty) 'constant' else 'other'"                 -> false
        )
      ) {
        fixture(
          s"List<String> relay(String value) => [$elements];",
          "void main(List<String> args) { final input = args[0]; sink(relay(input).join()); }"
        ) { (cpg, _) =>
          cpg.call
            .nameExact("sink")
            .argument
            .reachableByFlows(cpg.identifier.nameExact("input"))
            .nonEmpty shouldBe expected
          cpg.call.nameExact("<operator>.listAppend").nonEmpty shouldBe true
        }
      }
    }
    "retain set and map element updates with spread dependencies" in {
      for (
        (collection, operation, result) <- Seq(
          ("<String>{for (var i = 0; i < 2; i++) value}", "<operator>.setAdd", "items.join()"),
          ("<int, String>{for (var i = 0; i < 2; i++) i: value}", "<operator>.mapPut", "items[0]!"),
          (
            "<String>[...[value], for (var i = 0; i < 2; i++) 'constant']",
            "<operator>.collectionExtend",
            "items.join()"
          )
        )
      ) {
        fixture(
          s"String relay(String value) { final items = $collection; return $result; }",
          "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
        ) { (cpg, _) =>
          cpg.call.nameExact(operation).size shouldBe 1
          assertFlow(cpg, true)
        }
      }
    }
    "route returns through finally and exclude values replaced by abrupt cleanup" in {
      for (
        (cleanup, expected) <- Seq("cleanup();" -> true, "return 'constant';" -> false, "throw 'failure';" -> false)
      ) {
        fixture(
          s"void cleanup() {} String relay(String value) { try { return value; } finally { $cleanup } }",
          "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
        ) { (cpg, _) =>
          val original = cpg.method.nameExact("relay").ast.isReturn.codeExact("return value;").head
          original
            .out(io.shiftleft.codepropertygraph.generated.EdgeTypes.CFG)
            .exists(_.isInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.MethodReturn]) shouldBe false
          cpg.call
            .nameExact("sink")
            .argument
            .reachableByFlows(cpg.identifier.nameExact("input"))
            .nonEmpty shouldBe expected
        }
      }
    }
    "write late locals without first reading an uninitialized value" in {
      fixture(
        "",
        """void entry(String source) {
          |  late String assigned;
          |  assigned = source;
          |  sink(assigned);
          |  late final String once;
          |  once = source;
          |}
          |""".stripMargin
      ) { (cpg, _) =>
        val write = cpg.call.nameExact("<operator>.assignment").codeExact("assigned = source").head
        write.argument(1).isIdentifier shouldBe true
        cpg.call
          .nameExact("sink")
          .argument
          .reachableByFlows(cpg.method.nameExact("entry").parameter.nameExact("source"))
          .nonEmpty shouldBe true
        cpg.controlStructure
          .controlStructureTypeExact("THROW")
          .codeExact("LateInitializationError(once)")
          .size shouldBe 1
      }
    }
    "defer late local initializers in capturing thunks and guard reads and final writes" in {
      for (argument <- Seq("source", "'constant'")) {
        fixture(
          "String compute(String value) => value;",
          s"""void entry(String source) {
          |  late final result = compute($argument);
          |  String read() => result;
          |  sink(result);
          |  sink(read());
          |  late final String once;
          |  once = 'value';
          |}
          |""".stripMargin
        ) { (cpg, _) =>
          cpg.call.nameExact("compute").size shouldBe 1
          cpg.call.nameExact("compute").method.name.l shouldBe List("<late-init>")
          cpg.method.nameExact("entry").call.nameExact("compute").size shouldBe 0
          cpg.call.nameExact("<late-init>").size shouldBe 2
          cpg.call.nameExact("<late-init>").callee.isExternal.toList shouldBe List(false, false)
          cpg.call
            .nameExact("sink")
            .argument
            .reachableByFlows(cpg.method.nameExact("entry").parameter.nameExact("source"))
            .nonEmpty shouldBe (argument == "source")
          cpg.method.nameExact("entry").controlStructure.controlStructureTypeExact("THROW").nonEmpty shouldBe true
        }
      }
    }
    "defer late field initializers to guarded getters and keep uninitialized distinct from null" in {
      fixture(
        """String? initialize() => null;
          |class Box {
          |  late final String? value = initialize();
          |  late String assigned;
          |  late final String once;
          |  Box();
          |  Box.named();
          |}
          |""".stripMargin,
        "void main() { final box = Box(); box.value; box.assigned = 'set'; sink(box.assigned); box.once = 'once'; }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.method.nameExact("<init>", "named").isExternal(false).call.nameExact("initialize").size shouldBe 0
        cpg.call.nameExact("initialize").size shouldBe 1
        val getter = cpg.call.nameExact("initialize").method.head
        getter.fullName should include("GETTER:value")
        getter.call.nameExact("<operator>.isInitialized").nonEmpty shouldBe true
        cpg.call.nameExact("value").callee.isExternal.l shouldBe List(false)
        cpg.method.fullName(".*GETTER:assigned.*").controlStructure.controlStructureTypeExact("THROW").size shouldBe 1
        cpg.method.fullName(".*SETTER:once.*").controlStructure.controlStructureTypeExact("THROW").size shouldBe 1
        cpg.method.fullName(".*SETTER:assigned.*").controlStructure.controlStructureTypeExact("THROW").size shouldBe 0
      }
    }
    "keep static and top-level late initializers out of eager initialization methods" in {
      fixture(
        """String initialize() => 'value';
          |late String global = initialize();
          |class Lazy { static late final String field = initialize(); }
          |""".stripMargin,
        "void main() { sink(global); sink(Lazy.field); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.call.nameExact("initialize").size shouldBe 2
        cpg.call.nameExact("initialize").method.fullName.toList.foreach(_ should include("GETTER"))
        cpg.method.nameExact("<clinit>").call.nameExact("initialize").size shouldBe 0
      }
    }
    "resolve user operators and distinguish their returned arguments from constants" in {
      for (returned <- Seq("value", "'constant'")) {
        fixture(
          s"class Formatter { String operator +(String value) => $returned; }",
          "void main(List<String> args) { final input = args[0]; sink(Formatter() + input); }"
        ) { (cpg, _) =>
          val invocation = cpg.call.codeExact("Formatter() + input").head
          invocation.name shouldBe "+"
          invocation.callee.isExternal.l shouldBe List(false)
          invocation.argument(1).code shouldBe "input"
          cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty shouldBe
            (returned == "value")
        }
      }
    }
    "yield the assigned value independently of setter and index-setter returns" in {
      for (location <- Seq("Discard().value", "Discard()[0]")) {
        fixture(
          "class Discard { set value(String next) {} void operator []=(int index, String next) {} }",
          s"void main(List<String> args) { final input = args[0]; sink($location = input); }"
        ) { (cpg, _) =>
          cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty shouldBe true
          val setters = cpg.call.filter(call => call.methodFullName.contains("SETTER") || call.name == "[]=").l
          setters.iterator.callee.isExternal.l shouldBe List(false)
          setters.head.astParent.astChildren.toList
            .sortBy(_.order)
            .last
            .isInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Identifier] shouldBe true
        }
      }
    }
    "resolve indexed updates and overloaded increments without repeating receiver or index" in {
      fixture(
        """class Counter {
          |  Counter operator +(int amount) => this;
          |  Counter operator -() => this;
          |  bool operator ==(Object other) => false;
          |}
          |class Store {
          |  Counter operator [](int index) => Counter();
          |  void operator []=(int index, Counter value) {}
          |}
          |Store receiver() => Store();
          |int index() => 0;
          |""".stripMargin,
        "void main() { receiver()[index()]++; final value = -Counter(); final unequal = value != Counter(); }"
      ) { (cpg, _) =>
        cpg.call.nameExact("receiver").size shouldBe 1
        cpg.call.nameExact("index").size shouldBe 1
        for (name <- Seq("[]", "[]=", "+", "-", "==")) {
          withClue(name) { cpg.call.nameExact(name).callee.isExternal.l shouldBe List(false) }
        }
        cpg.call.nameExact("<operator>.logicalNot").argument.ast.isCall.nameExact("==").size shouldBe 1
      }
    }
    "guard null-aware index updates including their index and assigned value" in {
      fixture(
        """class Store { void operator []=(int index, String value) {} }
          |Store? receiver() => null;
          |int index() => 0;
          |String value() => 'value';
          |""".stripMargin,
        "void main() { receiver()?[index()] = value(); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.call.nameExact("receiver").size shouldBe 1
        val guard = cpg.call.nameExact("<operator>.conditional").head
        guard.argument(2).ast.isCall.name.toSet should contain allOf ("index", "value", "[]=")
        guard.argument(3).code shouldBe "null"
      }
    }
    "keep constructor-expanded and lazy field closures distinct with their receiver captures" in {
      fixture(
        """class Box {
          |  final callback = (String value) => value;
          |  late final reader = () => callback('value');
          |  Box();
          |  Box.named();
          |}
          |""".stripMargin,
        "void main() { Box(); Box.named(); }"
      ) { (cpg, _) =>
        val refs = cpg.methodRef.l
        refs.size shouldBe 3
        refs.map(_.methodFullName).distinct.size shouldBe 3
        refs.foreach(ref => ref.referencedMethod.isExternal shouldBe false)
        val captures = cpg.closureBinding.refOut.collect {
          case p: io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn => p
        }.l
        captures.map(_.method.fullName).distinct.size shouldBe 1
        captures.head.method.fullName should include("GETTER:reader")
      }
    }
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
    "propagate constructor field values through returned instances and bare returns" in {
      for (body <- Seq(";", "{ return; }"); creation <- Seq("Box(value)", "Box.create(value)")) {
        fixture(
          s"""class Box { final String value; Box(this.value)$body
             |  factory Box.create(String value) = Box;
             |}
             |String relay(String value) => $creation.value;
             |""".stripMargin,
          "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
        ) { (cpg, _) =>
          withClue(s"$creation, constructor body $body: ") { assertFlow(cpg, true) }
        }
      }
    }
    "isolate unrelated fields of returned objects" in {
      fixture(
        """class Box { final String value; final String other = 'fixed'; Box(this.value); }
          |String relay(String value) => Box(value).other;
          |""".stripMargin,
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }"
      ) { (cpg, _) =>
        assertFlow(cpg, false)
      }
    }
    "retain selected fields through wrappers, read aliases and exceptions" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/returned_fields.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for (
          (name, expected) <- Seq(
            "readValue"              -> true,
            "nestedValue"            -> true,
            "aliasValue"             -> true,
            "copiedField"            -> true,
            "capturedReceiver"       -> true,
            "compoundField"          -> true,
            "compoundOtherField"     -> false,
            "aliasWrite"             -> true,
            "guardedAlias"           -> true,
            "aliasWriteOther"        -> false,
            "reboundBase"            -> false,
            "capturedRebinding"      -> false,
            "overwrittenField"       -> false,
            "calleeOverwrite"        -> false,
            "calleeOtherOverwrite"   -> true,
            "calleeWrite"            -> true,
            "calleeWriteOther"       -> false,
            "calleeIndependent"      -> false,
            "calleeAliasOverwrite"   -> false,
            "calleeAliasPreserved"   -> true,
            "calleeConditional"      -> true,
            "calleeThrowing"         -> true,
            "calleeThrowAfterWrite"  -> false,
            "calleeRebind"           -> true,
            "calleeSavedValue"       -> true,
            "conditionalOverwrite"   -> true,
            "bothBranchesOverwrite"  -> false,
            "throwingOverwrite"      -> true,
            "reintroducedField"      -> true,
            "readBeforeOverwrite"    -> true,
            "parentFieldOverwrite"   -> false,
            "otherParentOverwrite"   -> true,
            "optionalLoopOverwrite"  -> true,
            "mandatoryLoopOverwrite" -> false,
            "caughtValue"            -> true,
            "readOther"              -> false,
            "nestedOther"            -> false,
            "independentMember"      -> false,
            "aliasOther"             -> false,
            "independentObjects"     -> false,
            "caughtOther"            -> false
          )
        ) {
          val method = cpg.method.nameExact(name).head
          withClue(name) {
            method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe expected
          }
        }
      }
    }
    "preserve captured binding identity and exclude replaced capture values" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/capture_isolation.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val results = Seq(
          "capturedValue"      -> true,
          "unrelatedLocal"     -> false,
          "overwrittenCapture" -> false,
          "conditionalCapture" -> true,
          "nestedCapture"      -> true,
          "shadowedCapture"    -> false,
          "localCapture"       -> true,
          "localOverwrite"     -> false
        ).map { case (name, expected) =>
          val method = cpg.method.nameExact(name).head
          val actual = method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty
          (name, actual, expected)
        }
        results.filter { case (_, actual, expected) => actual != expected } shouldBe empty
      }
    }
    "keep callable targets separate from mutable call arguments" in {
      fixture(
        """String relay(String value) => value;
          |String invoke(String input, String safe, String Function(String) callback) {
          |  callback(input);
          |  return callback(safe);
          |}
          |""".stripMargin,
        "void main(List<String> args) { final input = args[0]; sink(invoke(input, 'fixed', relay)); }"
      ) { (cpg, _) =>
        cpg.call.nameExact("callback").l.foreach { call =>
          call.argument.argumentIndex.l shouldBe List(1)
          call.receiver.argumentIndex.l shouldBe List(-1)
        }
        assertFlow(cpg, false)
      }
    }
    "expose predicate selection as a stock external-call approximation" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/iterable_selection.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for ((name, expected) <- Seq("selected" -> true, "elements" -> true, "unrelated" -> false)) {
          val method = cpg.method.nameExact(name).head
          val paths  = method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).l
          withClue(name) { paths.nonEmpty shouldBe expected }
          if (name == "selected") {
            paths.exists(
              _.elements.exists(_.isInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.MethodRef])
            ) shouldBe true
          }
        }
      }
    }
    "separate iterable element values from predicate selection and constant transforms" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/iterable_selection.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        val rules = new FullNameSemanticsParser()
          .parseFile(frontend.resolve("dataflow/iterable.semantics").toString)
          .map(_.copy(regex = true))
        val semantics = DefaultSemantics().plus(rules)
        semantics.initialize(cpg)
        val modeled = EngineContext(semantics = semantics)
        for (
          (name, expected) <- Seq(
            "selected"       -> false,
            "elements"       -> true,
            "unrelated"      -> false,
            "mapped"         -> true,
            "constantMapped" -> false
          )
        ) {
          val method = cpg.method.nameExact(name).head
          withClue(name) {
            val paths = method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input"))(modeled).l
            paths.nonEmpty shouldBe expected
          }
        }
        cpg.call.nameExact("where", "map", "join").callee.l.foreach(m => semantics.forMethod(m).isDefined shouldBe true)
      }
    }
    "report complete witnesses and explicit audit limits without certifying semantics" in {
      fixture(
        "String relay(String value) => value; String forward(String value) => relay(value); String fixed() => 'fixed';",
        "void main() {}"
      ) { (cpg, _) =>
        val probe = ujson.read("""{
          "id": "relay", "expected": true,
          "source": {"file": "lib/helper.dart", "method": "relay", "kind": "parameter", "name": "value"},
          "sink": {"file": "lib/helper.dart", "method": "relay", "kind": "return"}
        }""")
        val complete = CorpusDataflow.audit(cpg, Seq(probe), DefaultSemantics()).arr.head
        complete("passed").bool shouldBe true
        complete("outcome").str shouldBe "flow-observed"
        complete("semanticReview").str shouldBe "pending"
        complete("omittedWitnesses").num shouldBe 0
        complete("witnesses").arr.size.toDouble shouldBe complete("paths").num
        complete("witnesses").arr.head.arr.head("label").str shouldBe "METHOD_PARAMETER_IN"
        val forwarded = ujson.read(ujson.write(probe))
        forwarded("source")("method") = "forward"
        forwarded("sink")("method") = "forward"
        val nodes =
          CorpusDataflow.audit(cpg, Seq(forwarded), DefaultSemantics()).arr.head("witnesses").arr.flatMap(_.arr)
        val calls = nodes.flatMap(_.obj.get("call"))
        calls.map(_("name").str) should contain("relay")
        val relay = calls.find(_("name").str == "relay").get
        relay("callees").arr.exists(c => !c("external").bool && c("fullName") == relay("target")) shouldBe true
        val arguments = nodes.flatMap(_.obj.get("argumentsOf")).flatMap(_.arr)
        arguments.exists(c => c("name").str == "relay" && c("argumentIndex").num == 1) shouldBe true
        val truncated = CorpusDataflow.audit(cpg, Seq(probe), DefaultSemantics(), Some(0)).arr.head
        truncated("passed").bool shouldBe true
        truncated("witnesses").arr shouldBe empty
        truncated("omittedWitnesses").num shouldBe complete("paths").num
        val negative = ujson.read(ujson.write(probe))
        negative("id") = "independent"
        negative("expected") = false
        negative("sink")("method") = "fixed"
        val uncontrolled = CorpusDataflow.audit(cpg, Seq(probe, negative), DefaultSemantics()).arr.last
        uncontrolled("passed").bool shouldBe false
        uncontrolled("outcome").str shouldBe "inconclusive-positive-control"
        negative("positiveControl") = "relay"
        val controlled = CorpusDataflow.audit(cpg, Seq(probe, negative), DefaultSemantics()).arr.last
        controlled("passed").bool shouldBe true
        controlled("positiveControlSatisfied").bool shouldBe true
        controlled("outcome").str shouldBe "no-flow-observed-within-limits"
        val identityControl = ujson.read(ujson.write(probe))
        identityControl("sink") = identityControl("source")
        val identity = CorpusDataflow.audit(cpg, Seq(identityControl, negative), DefaultSemantics()).arr
        identity.head("passed").bool shouldBe true
        identity.last("passed").bool shouldBe false
        identity.last("positiveControlSatisfied").bool shouldBe false
        identity.last("outcome").str shouldBe "inconclusive-positive-control"
        negative("maxCallDepth") = 1
        CorpusDataflow.audit(cpg, Seq(probe, negative), DefaultSemantics()).arr.last("passed").bool shouldBe false
        probe("source")("name") = "missing"
        val invalid = CorpusDataflow.audit(cpg, Seq(probe), DefaultSemantics()).arr.head
        invalid("passed").bool shouldBe false
        invalid("outcome").str shouldBe "invalid-endpoints"
      }
    }
    "preserve completeError storage without mixing the error and stack trace" in {
      fixture(
        """import 'dart:async';
          |void report(Object error, StackTrace trace) {
          |  final completer = Completer<void>();
          |  completer.completeError(error, trace);
          |}
          |""".stripMargin,
        "void main() {}"
      ) { (cpg, _) =>
        val rules = new FullNameSemanticsParser()
          .parseFile(frontend.resolve("dataflow/async.semantics").toString)
          .map(_.copy(regex = true))
        rules.size shouldBe 1
        val semantics = DefaultSemantics().plus(rules)
        semantics.initialize(cpg)
        val modeled = EngineContext(semantics = semantics)
        val call    = cpg.call.nameExact("completeError").head
        semantics.forMethod(call.callee.head).isDefined shouldBe true
        val error = cpg.method.nameExact("report").parameter.nameExact("error").l
        val trace = cpg.method.nameExact("report").parameter.nameExact("trace").l
        call.argument.argumentIndex(0).reachableByFlows(error)(modeled).nonEmpty shouldBe true
        call.argument.argumentIndex(0).reachableByFlows(trace)(modeled).nonEmpty shouldBe true
        call.argument.argumentIndex(2).reachableByFlows(error)(modeled).isEmpty shouldBe true
        call.argument.argumentIndex(1).reachableByFlows(trace)(modeled).isEmpty shouldBe true
      }
    }
    "keep implicit receivers lexical inside cascade arguments and callbacks" in {
      fixture(
        """class Watch {
          |  void use(String value) {}
          |  void listen(void Function() callback) {}
          |}
          |class Owner {
          |  String value;
          |  Owner(this.value);
          |  String read() => value;
          |  void accept(String value) {}
          |  void register() {
          |    Watch()..use(read())..listen(() { accept(value); });
          |  }
          |}
          |""".stripMargin,
        "void main() {}"
      ) { (cpg, _) =>
        val read = cpg.call.nameExact("read").head
        read.receiver.isIdentifier.name.l shouldBe List("this")
        read.receiver.isIdentifier.refsTo.map(_.label).l shouldBe List("METHOD_PARAMETER_IN")
        val accept = cpg.call.nameExact("accept").head
        accept.receiver.isIdentifier.name.l shouldBe List("this")
        accept.receiver.isIdentifier.refsTo
          .collect { case local: io.shiftleft.codepropertygraph.generated.nodes.Local => local.closureBindingId }
          .flatten
          .nonEmpty shouldBe true
      }
    }
    "give part-file initializers unique identities and preserve symbol constants" in {
      fixture(
        "part 'first.dart'; part 'second.dart'; Symbol get tag => #ready;",
        "void main() {}",
        extraFiles = Map(
          "lib/first.dart"  -> "part of 'helper.dart'; final first = Object();",
          "lib/second.dart" -> "part of 'helper.dart'; final second = Object();"
        )
      ) { (cpg, _) =>
        val initializers = cpg.method.nameExact("<clinit>").fullName.l
        initializers.size shouldBe 2
        initializers.distinct.size shouldBe 2
        cpg.literal.codeExact("#ready").size shouldBe 1
        cpg.unknown.size shouldBe 0
      }
    }
    "link external tear-offs even when their target is never directly called" in {
      fixture("import 'dart:convert'; Object callback() => jsonEncode;", "void main() {}") { (cpg, _) =>
        val ref    = cpg.methodRef.codeExact("jsonEncode").head
        val target = cpg.method.fullNameExact(ref.methodFullName).l
        target.size shouldBe 1
        target.head.isExternal shouldBe true
        target.head.parameter.index.l.sorted shouldBe List(1, 2)
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
        cpg.member.nameExact("item").typeFullName.l shouldBe
          cpg.typeDecl.nameExact("Box").astChildren.isTypeDecl.nameExact("T").fullName.l
        cpg.member.nameExact("item").genericSignature.l shouldBe List("T?")
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
        cpg.call.nameExact("<operator>.addition").codeExact("'value: $input'").size shouldBe 1
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).nonEmpty shouldBe true
      }
    }
    "read dynamic object pattern fields from the matched receiver" in {
      fixture(
        "",
        """void main(Object? value) {
        |  if (value case dynamic(:var runtimeType)) print(runtimeType);
        |}""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.fieldIdentifier.canonicalNameExact("runtimeType").size shouldBe 1
        cpg.identifier.nameExact("runtimeType").l.foreach(_.refsTo.name.l shouldBe List("runtimeType"))
        cpg.call.nameExact("<operator>.fieldAccess").argument(1).isIdentifier.refsTo.size shouldBe 1
      }
    }
    "retain enhanced enum constructors, constants and method bodies" in {
      fixture(
        """enum Mode {
          |  first('one'), second.named('two');
          |  final String text;
          |  const Mode(this.text);
          |  const Mode.named(this.text);
          |  String relay(String value) => value;
          |}
          |enum Plain { first, second }
          |""".stripMargin,
        "void main(List<String> args) { final input = args[0]; sink(Mode.first.relay(input)); }"
      ) { (cpg, _) =>
        cpg.typeDecl.nameExact("Mode", "Plain").size shouldBe 2
        cpg.member.nameExact("first", "second").size shouldBe 4
        cpg.call.nameExact("relay").callee.isExternal(false).size shouldBe 1
        cpg.method.fullName(".*:ENUM:Mode:<clinit>").ast.isCall.nameExact("<init>", "named").size shouldBe 2
        cpg.unknown.size shouldBe 0
        assertFlow(cpg, true)
      }
    }
    "preserve assertion evaluation and route labeled loop jumps" in {
      fixture(
        "class Box { Box(bool flag) : assert(flag, 'invalid'); }",
        """bool check() => true;
          |String message() => 'failed';
          |void main() {
          |  assert(check(), message());
          |  outer: for (var i = 0; i < 3; i++) {
          |    while (check()) { if (check()) continue outer; break outer; }
          |  }
          |  sink('done');
          |}
          |""".stripMargin,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("<operator>.assertionsEnabled").size shouldBe 2
        val failure = cpg.controlStructure.codeExact("<assertion failure>").l
        failure.size shouldBe 2
        cpg.call.nameExact("message").cfgNext.l should contain(failure.find(_.method.name == "main").get)
        val continued = cpg.controlStructure.codeExact("continue outer;").cfgNext.l
        continued.map(_.code).exists(_.startsWith("<continue>")) shouldBe true
        continued.iterator.cfgNext.code.l should contain("i")
        val broken = cpg.controlStructure.codeExact("break outer;").cfgNext.l
        broken.map(_.code).exists(_.startsWith("<break>")) shouldBe true
        broken.iterator.cfgNext.code.l should contain("'done'")
      }
    }
    "retain increment bindings and evaluate accessor receivers once" in {
      fixture(
        """class Box {
          |  int field = 0;
          |  int get value => field;
          |  set value(int next) { field = next; }
          |  Function callback() => () { field++; };
          |}
          |Box make() => Box();
          |""".stripMargin,
        "void main() { var count = 0; count++; --count; var old = make().value++; var next = ++make().value; }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.identifier.nameExact("count").l.foreach(_.refsTo.name.l shouldBe List("count"))
        cpg.identifier.nameExact("field").size shouldBe 0
        cpg.fieldIdentifier.canonicalNameExact("field").size should be > 0
        cpg.call.nameExact("make").size shouldBe 2
        cpg.call.callee.fullName(".*:GETTER:value").size shouldBe 2
        cpg.call.callee.fullName(".*:SETTER:value").size shouldBe 2
        cpg.local.filter(_.closureBindingId.nonEmpty).name.toSet shouldBe Set("this")
        val updates = cpg.call.nameExact("<operator>.assignment").code("<tmp>.*make.*").l
        updates.nonEmpty shouldBe true
      }
    }
    "capture write-only variables, callable parameters and implicit receivers in closures" in {
      fixture(
        """class Box {
          |  bool active = true;
          |  void reset() {}
          |  Function callback(void Function() action) {
          |    var written = 0;
          |    return () { active = false; written = 1; reset(); action(); };
          |  }
          |}
          |""".stripMargin,
        "void main() {}",
        dataflow = false
      ) { (cpg, _) =>
        cpg.local.filter(_.closureBindingId.nonEmpty).name.toSet shouldBe Set("this", "written", "action")
        cpg.closureBinding.size shouldBe 3
        val captured = cpg.identifier
          .nameExact("this", "written", "action")
          .filter(_.refsTo.exists {
            case local: io.shiftleft.codepropertygraph.generated.nodes.Local => local.closureBindingId.nonEmpty
            case _                                                           => false
          })
          .l
        captured.map(_.name).toSet shouldBe Set("this", "written", "action")
        captured.foreach { id =>
          id.refsTo.collect { case local: io.shiftleft.codepropertygraph.generated.nodes.Local =>
            local
          }.size shouldBe 1
        }
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
        cpg.method.fullName(".*:GETTER:global").literal.code.l should contain("'global'")
        cpg.method.nameExact("<init>").isExternal(false).literal.code.l should contain("'initial'")
        cpg.call.nameExact("write").callee.isExternal.l shouldBe List(false)
        cpg.call.nameExact("write").argument(1).isIdentifier.refsTo.size shouldBe 1
        cpg.literal.codeExact("'updated'").size shouldBe 1
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
        cpg.call.nameExact("create").argument(0).isIdentifier.refsTo.size shouldBe 1
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
          cpg.call.nameExact("callback").argument.argumentIndex.toSet shouldBe Set(1, 2)
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
    "represent generic aliases, type literals and callable object tear-offs" in {
      fixture(
        """typedef Callback<T extends num> = T Function(T value);
          |typedef Legacy<T>(T value);
          |class Callable { String call(String value) => value; }
          |String relay(String value) {
          |  final String Function(String) callback = Callable();
          |  return callback(value);
          |}
          |""".stripMargin,
        "void main(List<String> args) { final type = List<String>; final input = args[0]; sink(relay(input)); }"
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.typeDecl.nameExact("Callback").aliasTypeFullName.l should not be empty
        cpg.typeDecl.nameExact("Legacy").aliasTypeFullName.l should not be empty
        cpg.typeRef.codeExact("List<String>").size shouldBe 1
        cpg.call.nameExact("callback").callee.name.l shouldBe List("<bound>")
        assertFlow(cpg, true)
      }
    }
    "forward mixin application constructors and preserve mixin dispatch" in {
      fixture(
        """class Base { final String value; Base(this.value); Base.named({required this.value}); }
          |mixin First { String tag() => 'first'; }
          |mixin Last { String tag() => 'last'; }
          |class Applied = Base with First, Last;
          |String relay(String value) => Applied.named(value: value).value;
          |""".stripMargin,
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); Applied('fixed').tag(); }"
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        val applied = cpg.typeDecl.nameExact("Applied").head
        applied.method.name.toSet should contain allOf ("<init>", "named")
        applied.method.nameExact("named").parameter.name.toSet shouldBe Set("this", "value")
        cpg.call.nameExact("tag").callee.astParentFullName.l.exists(_.endsWith(":MIXIN:Last")) shouldBe true
        assertFlow(cpg, true)
      }
    }
    "route continue-to-case past the target condition and guard" in {
      for (target <- Seq("case 1:", "case > 0 when true:")) {
        fixture(
          "void mark(String value) {}",
          s"""
          |void main() {
          |  switch (0) {
          |    case 0: continue selected;
          |    selected: $target mark('target');
          |    default: mark('default');
          |  }
          |}
          |""".stripMargin,
          dataflow = false
        ) { (cpg, _) =>
          cpg.unknown.size shouldBe 0
          val jump       = cpg.controlStructure.codeExact("continue selected;").head
          val targetNode = jump.cfgNext.head
          targetNode.label shouldBe "JUMP_TARGET"
          targetNode.cfgNext.code.l shouldBe List("'target'")
          cpg.jumpLabel.name.l should contain(
            targetNode.asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.JumpTarget].name
          )
        }
      }
    }
    "guard null-aware collection elements and evaluate map keys before values" in {
      fixture(
        "String? key() => null; String? value() => 'value';",
        "void main() { final list = [?value()]; final map = {?key(): ?value()}; }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("key").size shouldBe 1
        cpg.call.nameExact("value").size shouldBe 2
        cpg.controlStructure.controlStructureTypeExact("IF").size shouldBe 3
        val entry = cpg.call.nameExact("<operator>.keyValueAssociation").head
        entry.argument.isIdentifier.size shouldBe 2
        cpg.call.nameExact("value").l.foreach(_.cfgNext.nonEmpty shouldBe true)
      }
    }
    "model records, destructuring, guarded patterns and switch expressions" in {
      fixture(
        "String relay(String value) => value;",
        """
        (String, {int count}) source(String text) => (text, count: 2);
        void main(List<String> args) {
          final input = args[0];
          final (value, count: count) = source(input);
          var left = ''; var right = 0;
          (left, right) = (value, count);
          if ((left, right) case (var text, > 0) when text.isNotEmpty) sink(relay(text));
          final selected = switch (value) { 'skip' => 'constant', var text when text.isNotEmpty => text, _ => '' };
          sink(relay(selected));
        }
      """,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("source").size shouldBe 1
        cpg.call.nameExact("<operator>.record").size shouldBe 3
        cpg.controlStructure.controlStructureTypeExact("IF").size shouldBe 14
        cpg.call.nameExact("<operator>.isInitialized").size shouldBe 8
        cpg.local.nameExact("value", "count", "left", "right", "text", "selected").size should be >= 7
        cpg.identifier.nameExact("text").refsTo.name.toSet shouldBe Set("text")
        cpg.call.nameExact("<operator>.fieldAccess").argument(2).code.toSet should contain allOf ("$1", "count")
      }
    }
    "preserve positive and negative flow through switch expression bindings" in {
      for ((result, expected) <- Seq("text" -> true, "'constant'" -> false)) {
        fixture(
          "String relay(String value) => value;",
          s"""
          void main(List<String> args) {
            final input = args[0];
            final selected = switch (input) { var text => $result };
            sink(relay(selected));
          }
        """
        ) { (cpg, _) => assertFlow(cpg, expected) }
      }
    }
    "retain list, map, object, null and logical patterns and joined variable references" in {
      fixture(
        "class Box { final String value; Box(this.value); }",
        """
        void main(Object? obj) {
          if (obj case [String first, ...var middle, String last]) sink(first);
          if (obj case {'key': String value}) sink(value);
          if (obj case Box(value: var text)) sink(text);
          if (obj case (String value) || [String value]) sink(value);
          if (obj case var nonNull?) print(nonNull);
          final result = switch (1) { > 0 && < 10 => 1, _ => 0 };
          switch (obj) { case Box(value: var content) when content.isNotEmpty: sink(content); break; default: break; }
          for (final (a, b) in [(1, 2)]) print(a + b);
        }
      """,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("sublist").size shouldBe 1
        cpg.identifier.nameExact("value").refsTo.name.toSet shouldBe Set("value")
        cpg.local.nameExact("a", "b").size shouldBe 2
        cpg.controlStructure.controlStructureTypeExact("SWITCH").size shouldBe 1
      }
    }
    "model mixins, extensions, extension types and class modifiers" in {
      fixture(
        """
        base mixin Echo { String echo(String value) => value; }
        sealed class Root {}
        final class Leaf extends Root with Echo {}
        abstract interface class Contract { String run(); }
        extension TextOps on String { String wrap() => this; }
        extension type Label(String value) { String read() => value; }
      """,
        """
        void main() { final leaf = Leaf(); sink(leaf.echo('x')); sink('x'.wrap()); sink(TextOps('z').wrap()); sink(Label('y').read()); }
      """,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.typeDecl.nameExact("Echo", "TextOps", "Label").isExternal(false).size shouldBe 3
        cpg.typeDecl.nameExact("Leaf").inheritsFromTypeFullName.exists(_.contains("Echo")) shouldBe true
        cpg.call.nameExact("echo", "wrap", "read").callee.isExternal(false).size shouldBe 4
        cpg.typeDecl.nameExact("Label").member.name.l should contain("value")
        cpg.call.nameExact("<init>").codeExact("Label('y')").callee.isExternal.l shouldBe List(false)
        cpg.call.nameExact("wrap").dispatchType.toSet shouldBe Set("STATIC_DISPATCH")
        cpg.annotation.name.toSet should contain allOf ("base", "sealed", "final", "interface")
      }
    }
    "keep anonymous extensions distinct and link named representation constructors" in {
      fixture(
        """
        extension on String { String echo() => this; }
        extension on int { int twice() => this * 2; }
        extension type Label.named(String value) {}
        String use(String value) => value.echo();
        int count(int value) => value.twice();
      """,
        "void main() { sink(use(Label.named('x').value)); print(count(1)); }",
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.typeDecl.name("<extension>.*").fullName.toSet.size shouldBe 2
        cpg.call.nameExact("echo", "twice", "named").callee.isExternal(false).size shouldBe 3
      }
    }
    "lower collection spreads, conditionals and loops without duplicating evaluation" in {
      fixture(
        "List<String>? source() => ['x'];",
        """
        void build(bool flag) {
          final values = [...?source(), if (flag) 'yes' else 'no', for (var i = 0; i < 2; i++) '$i',
            for (final (a, b) in [(1, 2)]) '$a$b'];
          final map = {if (flag) 'key': 'value', ...{'a': 'b'}};
          print(values); print(map);
        }
      """,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("source").size shouldBe 1
        cpg.call.nameExact("<operator>.spread").size shouldBe 2
        cpg.controlStructure.controlStructureTypeExact("FOR", "WHILE").size shouldBe 2
        cpg.controlStructure.controlStructureTypeExact("IF").size shouldBe 5
        cpg.call.nameExact("<operator>.isInitialized").size shouldBe 2
      }
    }
    "mark async functions, await, yields and stream iteration" in {
      fixture(
        """
        Future<String> relay(String value) async => value;
        Iterable<String> syncValues(String value) sync* { yield value; yield* [value]; }
        Stream<String> values(String value) async* { yield await relay(value); }
      """,
        """
        Future<void> main() async { await for (final value in values('x')) { sink(value); } }
      """,
        dataflow = false
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.annotation.nameExact("async").size shouldBe 3
        cpg.annotation.nameExact("generator").size shouldBe 2
        cpg.call.nameExact("<operator>.await").size shouldBe 2
        cpg.call.nameExact("<operator>.yield", "<operator>.yieldAll").size shouldBe 3
        cpg.call.nameExact("<operator>.streamIterator").size shouldBe 1
        cpg.controlStructure.controlStructureTypeExact("WHILE").size shouldBe 1
      }
    }
    "track direct await value flow without treating constants as dependent" in {
      for ((result, expected) <- Seq("value" -> true, "'constant'" -> false)) {
        fixture(
          s"Future<String> relay(String value) async => $result;",
          """
          Future<void> main(List<String> args) async {
            final input = args[0]; sink(await relay(input));
          }
        """
        ) { (cpg, _) => assertFlow(cpg, expected) }
      }
    }
    "cache comparison invocations while preserving arguments and receiver direction" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/comparison_cache.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        for (
          (name, count) <- Seq(
            "constants"  -> 1,
            "equal"      -> 1,
            "negation"   -> 1,
            "directions" -> 2,
            "logical"    -> 1,
            "distinct"   -> 2,
            "separate"   -> 2,
            "nested"     -> 4
          )
        ) {
          withClue(name) {
            val method = cpg.method.nameExact(name).head
            method.local.name("<pattern>.*").size shouldBe count
            method.call.nameExact("==", ">").callee.isExternal.toSet shouldBe Set(false)
            val storage = method.local.name("<pattern>.*").id.toSet
            method.call.nameExact("<operator>.isInitialized").argument.isIdentifier.refsTo.id.toSet shouldBe storage
          }
        }
      }
    }
    "reuse extension pattern invocations only with equal substitutions" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/extension_cache.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        for ((name, expected) <- Seq("repeated" -> 1, "substituted" -> 2)) {
          val calls = cpg.method.nameExact(name).call.nameExact("kind").l
          calls.size shouldBe 2
          calls.iterator.callee.isExternal.toSet shouldBe Set(false)
          calls
            .flatMap(
              _.astParent
                .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
                .argument(1)
                .start
                .isIdentifier
                .refsTo
                .id
                .l
            )
            .toSet
            .size shouldBe expected
        }
        cpg.method.nameExact("compared").local.name("<pattern>.*").size shouldBe 1
        cpg.method.nameExact("compared").call.nameExact(">").dispatchType.toSet shouldBe Set("STATIC_DISPATCH")
      }
    }
    "resolve map pattern calls and share lazy storage by constant key" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/map_patterns.dart")), "void main() {}") {
        (cpg, _) =>
          cpg.unknown.size shouldBe 0
          for ((name, expected) <- Seq("selected" -> true, "independent" -> false, "constant" -> false)) {
            val method = cpg.method.nameExact(name).head
            withClue(name) {
              method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe expected
            }
          }
          val generic =
            cpg.method.nameExact("generic").call.nameExact(Operators.instanceOf).argument.isTypeRef.typeFullName.toSet
          generic should contain("T")
          val nullableChecks = cpg.method
            .nameExact("generic")
            .call
            .nameExact(Operators.instanceOf)
            .filter(_.argument(1).code == "null")
            .l
          nullableChecks.size shouldBe 1
          val nullableCheck = nullableChecks.head
          val presenceGuard = nullableCheck.astParent.asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
          presenceGuard.name shouldBe Operators.logicalAnd
          presenceGuard.argument(2).ast.isCall.nameExact("containsKey").size shouldBe 1
          nullableCheck.cfgNext.l should contain(presenceGuard)
          val nonNullGuard = presenceGuard.astParent.asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
          nonNullGuard.name shouldBe Operators.logicalOr
          nonNullGuard.argument(1).cfgNext.l should contain(nonNullGuard)
          cpg.method.nameExact("nestedMember").local.name("<pattern>.*").size shouldBe 3
          val nested = cpg.method.nameExact("nested").call.nameExact("[]").l
          nested
            .flatMap(
              _.astParent
                .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
                .argument(1)
                .start
                .isIdentifier
                .refsTo
                .id
                .l
            )
            .toSet
            .size shouldBe 4
          for (
            (name, slots) <- Seq(
              "wildcard"      -> 1,
              "typedWildcard" -> 1,
              "cases"         -> 1,
              "nullKey"       -> 1,
              "guarded"       -> 1,
              "multiple"      -> 2,
              "separate"      -> 2
            )
          ) {
            val method = cpg.method.nameExact(name).head
            method.call.nameExact(Operators.indexAccess, "<operator>.patternShape").size shouldBe 0
            for (member <- Seq("[]", "containsKey")) {
              val calls = method.call.nameExact(member).l
              withClue(s"$name $member") {
                calls.nonEmpty shouldBe true
                calls.iterator.callee.isExternal.toSet shouldBe Set(false)
                val storage = calls
                  .flatMap(
                    _.astParent
                      .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
                      .argument(1)
                      .start
                      .isIdentifier
                      .refsTo
                      .id
                      .l
                  )
                  .toSet
                storage.size shouldBe slots
                val guarded = method.call.nameExact("<operator>.isInitialized").argument.isIdentifier.refsTo.id.toSet
                storage.subsetOf(guarded) shouldBe true
              }
            }
            method.call.nameExact(Operators.instanceOf).argument.isTypeRef.typeFullName.toSet.size shouldBe
              (if (name == "typedWildcard") 3 else 2)
          }
      }
    }
    "resolve and cache list pattern members while skipping untyped wildcards" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/list_patterns.dart")), "void main() {}") {
        (cpg, _) =>
          for (
            (name, indexed) <- Seq(
              "wildcards"           -> 0,
              "empty"               -> 0,
              "prefixRest"          -> 1,
              "typedWildcard"       -> 1,
              "headAndRestWildcard" -> 0,
              "cases"               -> 1,
              "rest"                -> 2,
              "tail"                -> 1
            )
          ) {
            val method  = cpg.method.nameExact(name).head
            val lengths = method.call.nameExact("length").l
            withClue(name) {
              lengths.nonEmpty shouldBe true
              lengths.iterator.callee.isExternal.toSet shouldBe Set(false)
              val indices = method.call.nameExact("[]").l
              indices.iterator.callee.isExternal.toSet shouldBe (if (indexed == 0) Set.empty else Set(false))
              def storage(calls: List[io.shiftleft.codepropertygraph.generated.nodes.Call]): Set[Long] =
                calls
                  .flatMap(
                    _.astParent
                      .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
                      .argument(1)
                      .start
                      .isIdentifier
                      .refsTo
                      .id
                      .l
                  )
                  .toSet
              storage(lengths).size shouldBe 1
              storage(indices).size shouldBe indexed
              method.call.nameExact(Operators.indexAccess).size shouldBe 0
            }
          }
          cpg.method.nameExact("restWildcard").call.nameExact("length", "[]", "sublist").size shouldBe 0
          cpg.method.nameExact("all").call.nameExact("length", "[]").size shouldBe 0
          cpg.method.nameExact("all").call.nameExact("sublist").argument(2).code.l shouldBe List("null")
          cpg.method.nameExact("prefixRest").call.nameExact("sublist").argument(2).code.l shouldBe List("null")
          val rest = cpg.method.nameExact("rest").call.nameExact("sublist").head
          rest.callee.isExternal.toSet shouldBe Set(false)
          rest.argument(1).code shouldBe "1"
          rest.argument(2).start.isCall.name.l shouldBe List(Operators.subtraction)
      }
    }
    "share lazy pattern getter storage across cases and separate nested paths" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/pattern_cache.dart")), "void main() {}") {
        (cpg, _) =>
          for (
            (name, slots) <- Seq(
              "cases"      -> 1,
              "guarded"    -> 1,
              "statements" -> 1,
              "nested"     -> 2,
              "wildcard"   -> 1,
              "logical"    -> 1,
              "separate"   -> 2
            )
          ) {
            val method  = cpg.method.nameExact(name).head
            val getters = method.call.nameExact("item").l
            val storage = getters.flatMap { getter =>
              getter.astParent
                .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
                .argument(1)
                .start
                .isIdentifier
                .refsTo
                .id
                .l
            }.toSet
            withClue(name) {
              storage.size shouldBe slots
              val guarded = method.call.nameExact("<operator>.isInitialized").argument.isIdentifier.refsTo.id.toSet
              storage.subsetOf(guarded) shouldBe true
            }
          }
        val results = Seq("direct", "wrapped", "nestedDirect", "selected", "independent").map { name =>
          val diagnostics                     = new QueryDiagnostics
          implicit val context: EngineContext =
            EngineContext(config = EngineConfig(diagnostics = Some(diagnostics)))
          val method = cpg.method.nameExact(name).head
          val flow   = method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty
          (name, flow, diagnostics.limitations)
        }
        results shouldBe Seq(
          ("direct", true, Set.empty),
          ("wrapped", true, Set.empty),
          ("nestedDirect", true, Set.empty),
          ("selected", true, Set.empty),
          ("independent", false, Set.empty)
        )
      }
    }
    "resolve pattern comparison operators with the correct receiver" in {
      fixture(
        Files.readString(frontend.resolve("src/test/resources/semantics/pattern_operators.dart")),
        "void main() {}"
      ) { (cpg, _) =>
        for (
          (name, operator, inputIndex) <- Seq(
            ("constantPattern", "==", 1),
            ("equalPattern", "==", 0),
            ("unequalPattern", "==", 0),
            ("greaterPattern", ">", 0),
            ("binaryEqual", "==", 0),
            ("binaryUnequal", "==", 0)
          )
        ) {
          val method = cpg.method.nameExact(name).head
          val calls  = method.call.nameExact(operator).l
          withClue(name) {
            calls.size shouldBe 1
            method.call.nameExact(Operators.conditional).foreach(_.typeFullName shouldBe "bool")
            val call = calls.head
            call.callee.isExternal(false).size shouldBe 1
            call.argument(inputIndex).start.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe true
            call
              .argument(1 - inputIndex)
              .start
              .reachableByFlows(method.parameter.nameExact("input"))
              .nonEmpty shouldBe false
          }
        }
        for (name <- Seq("nullPattern", "equalNullPattern", "binaryNull")) {
          withClue(name) { cpg.method.nameExact(name).call.nameExact("==").size shouldBe 0 }
        }
        for (name <- Seq("binaryEqual", "ordered", "dynamicEqual", "dynamicUnequal")) {
          val method = cpg.method.nameExact(name).head
          val guard  = method.call.nameExact(Operators.conditional).head
          guard
            .argument(1)
            .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
            .name shouldBe Operators.logicalAnd
          guard.argument(2).asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call].name shouldBe "=="
          guard
            .argument(3)
            .asInstanceOf[io.shiftleft.codepropertygraph.generated.nodes.Call]
            .name shouldBe Operators.equals
          guard.argument(1).cfgNext.l should contain(guard.argument(3).astMinusRoot.isIdentifier.head)
          method.call.nameExact("==").size shouldBe 1
        }
        val ordered = cpg.method.nameExact("ordered").head
        ordered.call.nameExact("marked").size shouldBe 2
        val visited = scala.collection.mutable.Set.empty[Long]
        def marks(node: io.shiftleft.codepropertygraph.generated.nodes.CfgNode): List[String] = {
          if (!visited.add(node.id)) Nil
          else {
            val current = node match {
              case call: io.shiftleft.codepropertygraph.generated.nodes.Call if call.name == "marked" =>
                List(call.argument(1).code)
              case _ => Nil
            }
            current ++ node.cfgNext.l.flatMap(marks)
          }
        }
        marks(ordered) shouldBe List("'left'", "'right'")
      }
    }
    "isolate record fields through calls, nested records and destructuring" in {
      fixture(Files.readString(frontend.resolve("src/test/resources/semantics/record_fields.dart")), "void main() {}") {
        (cpg, _) =>
          val results = Seq(
            "first"              -> true,
            "second"             -> false,
            "namedField"         -> true,
            "namedOther"         -> false,
            "mixedField"         -> true,
            "mixedOther"         -> false,
            "mixedPattern"       -> true,
            "destructuredFirst"  -> true,
            "destructuredSecond" -> false,
            "namedPattern"       -> false,
            "nestedFirst"        -> true,
            "nestedSecond"       -> false,
            "replacedRecord"     -> false,
            "independentRecord"  -> false
          ).map { case (name, expected) =>
            val method = cpg.method.nameExact(name).head
            val actual = method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty
            (name, actual, expected)
          }
        results.filter { case (_, actual, expected) => actual != expected } shouldBe empty
        val ordered = cpg.method.nameExact("ordered").head
        ordered.call.nameExact("marked").size shouldBe 2
        val visited = scala.collection.mutable.Set.empty[Long]
        def marks(node: io.shiftleft.codepropertygraph.generated.nodes.CfgNode): List[String] = {
          if (!visited.add(node.id)) Nil
          else {
            val current = node match {
              case call: io.shiftleft.codepropertygraph.generated.nodes.Call if call.name == "marked" =>
                List(call.argument(2).code)
              case _ => Nil
            }
            current ++ node.cfgNext.l.flatMap(marks)
          }
        }
        marks(ordered) shouldBe List("'second'", "'first'")
      }
    }
    "retain whole-record value dependencies through destructuring" in {
      for ((value, expected) <- Seq("input" -> true, "'constant'" -> false)) {
        fixture(
          "String relay(String value) => value;",
          s"""
          void main(List<String> args) {
            final input = args[0]; final (text, count: count) = ($value, count: 1);
            sink(relay(text));
          }
        """
        ) { (cpg, _) => assertFlow(cpg, expected) }
      }
    }
    "link configured workspace packages and existing generated sources" in {
      fixture(
        """
        import 'package:dependency/api.dart';
        part 'helper.g.dart';
        String relay(String value) => generated(value);
      """,
        "void main(List<String> args) { final input = args[0]; sink(relay(input)); }",
        extraFiles = Map(
          "pubspec.yaml" -> "name: proof\nenvironment:\n  sdk: ^3.9.2\nworkspace:\n  - packages/dependency\n",
          "packages/dependency/pubspec.yaml" -> "name: dependency\nresolution: workspace\nenvironment:\n  sdk: ^3.9.2\n",
          "packages/dependency/lib/api.dart"      -> "export 'fallback.dart' if (dart.library.io) 'io.dart';",
          "packages/dependency/lib/fallback.dart" -> "String forward(String value) => value;",
          "packages/dependency/lib/io.dart"       -> "String forward(String value) => 'io';",
          "lib/helper.g.dart" -> "part of 'helper.dart'; String generated(String value) => forward(value);",
          ".dart_tool/package_config.json" -> """{"configVersion":2,"packages":[
          {"name":"proof","rootUri":"../","packageUri":"lib/","languageVersion":"3.9"},
          {"name":"dependency","rootUri":"../packages/dependency","packageUri":"lib/","languageVersion":"3.9"}
        ]}"""
        )
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        cpg.call.nameExact("forward").callee.filename.l shouldBe List("packages/dependency/lib/fallback.dart")
        cpg.call.nameExact("generated").callee.filename.l shouldBe List("lib/helper.g.dart")
        cpg.imports.code("export.*").importedEntity.l shouldBe List("fallback.dart")
        assertFlow(cpg, true)
      }
    }
    "select conditional exports for VM and web without mixing their flows" in {
      for (environment <- Seq("analyzer-default", "vm", "web")) {
        fixture(
          "export 'fallback.dart' if (dart.library.io) 'vm.dart' if (dart.library.html) 'web.dart';",
          "void main(List<String> args) { final input = args[0]; sink(relay(input)); }",
          extraFiles = Map(
            "lib/fallback.dart" -> "String relay(String value) => 'fallback';",
            "lib/vm.dart"       -> "String relay(String value) => value;",
            "lib/web.dart"      -> "String relay(String value) => 'web';"
          ),
          environment = environment
        ) { (cpg, _) =>
          val selected = if (environment == "analyzer-default") "fallback" else environment
          cpg.call.nameExact("relay").callee.filename.l shouldBe List(s"lib/$selected.dart")
          assertFlow(cpg, environment == "vm")
        }
      }
    }
    "resolve Flutter widgets and retain callback captures without a build" in {
      if (!sys.env.get("DART_FLUTTER_TESTS").contains("1"))
        cancel("Set DART_FLUTTER_TESTS=1 after preparing the Flutter fixture")
      val resources     = frontend.resolve("src/test/resources/flutter")
      val packageConfig = Files.readString(
        Paths.get(
          sys.env.getOrElse(
            "DART_FLUTTER_PACKAGE_CONFIG",
            repository.resolve("agents/flutter-fixture/.dart_tool/package_config.json").toString
          )
        )
      )
      fixture(
        "",
        "",
        extraFiles = Map(
          "bin/main.dart"                  -> Files.readString(resources.resolve("lib/main.dart")),
          ".dart_tool/package_config.json" -> packageConfig
        )
      ) { (cpg, _) =>
        cpg.unknown.size shouldBe 0
        val buttons = cpg.call.methodFullName("package:flutter/.*text_button.dart.*").l
        buttons.size shouldBe 2
        buttons.foreach { button =>
          button.argument.argumentNameExact("onPressed").isMethodRef.size shouldBe 1
          button.argument
            .argumentNameExact("child")
            .ast
            .isCall
            .methodFullName("package:flutter/.*text.dart.*")
            .size shouldBe 1
        }
        cpg.closureBinding.size shouldBe 1
        cpg.local.nameExact("input").closureBindingId.size shouldBe 1
        cpg.call
          .codeExact("sink(input)")
          .argument
          .reachableByFlows(cpg.identifier.nameExact("input"))
          .nonEmpty shouldBe true
        cpg.call
          .codeExact("sink('constant')")
          .argument
          .reachableByFlows(cpg.identifier.nameExact("input"))
          .isEmpty shouldBe true
      }
    }
    "produce an empty reloadable graph and report excluded files" in {
      val dir = Files.createTempDirectory(Files.createDirectories(repository.resolve("agents")), "empty dart λ ")
      try {
        val report                       = dir.resolve("report.json")
        val output                       = dir.resolve("cpg.bin")
        def scan(settings: Config): Unit = {
          val cpg =
            new DartSrc2Cpg().createCpg(settings.withInputPath(dir.toString).withOutputPath(output.toString)).get
          cpg.method.isExternal(false).size shouldBe 0
          cpg.close()
          val loaded = Cpg.withStorage(output)
          try loaded.metaData.language.l shouldBe List("DART")
          finally loaded.close()
        }
        scan(config.copy(report = report.toString))
        val coverage = ujson.read(Files.readString(report))
        coverage("includedFiles").num shouldBe 0
        coverage("stringConversionOrder").str shouldBe "after-expression-evaluation"
        coverage("exporter")("exporterVersion").str shouldBe "0.3.18"
        coverage("exporter")("sdkVersion").str shouldBe "3.9.2"
        coverage("exporter")("analyzerVersion").str shouldBe "8.4.1"
        Files.writeString(dir.resolve("excluded.dart"), "void excluded() {}")
        scan(config.copy(report = report.toString).withIgnoredFilesRegex(".*excluded[.]dart"))
        ujson.read(Files.readString(report))("skippedFiles").num shouldBe 1
        scan(config.copy(report = report.toString, environment = "web").withIgnoredFilesRegex(".*excluded[.]dart"))
        ujson.read(Files.readString(report))("stringConversionOrder").str shouldBe "per-expression"
      } finally FileUtil.delete(dir)
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
        "sdkVersion"      -> "3.9.2",
        "exporterVersion" -> "0.3.18"
      )
      intercept[IllegalArgumentException](ExportProtocol.units(Seq(valid)))
      intercept[IllegalArgumentException](
        ExportProtocol.units(Seq(valid, ujson.Obj("record" -> "summary", "files" -> 1)))
      )
    }
  }
}
