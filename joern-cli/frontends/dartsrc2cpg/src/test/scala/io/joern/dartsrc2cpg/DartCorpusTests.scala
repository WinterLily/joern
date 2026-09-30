package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.FullNameSemanticsParser
import io.joern.dataflowengineoss.passes.reachingdef.{ReachingDefProblem, ReachingDefTransferFunction}
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.validation.{PostFrontendValidator, ValidationLevel}
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DartCorpusTests extends AnyWordSpec with Matchers {
  implicit val resolver: ICallResolver = NoResolve
  private val repository               = Iterator
    .iterate(Path.of(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.exists(path.resolve("project/Projects.scala")))
    .get
  private val frontend     = repository.resolve("joern-cli/frontends/dartsrc2cpg")
  private val applications = sys.env.contains("DART_APPLICATION_TESTS")
  private val holdout      = sys.env.contains("DART_HOLDOUT_TESTS")
  private val corpus       =
    frontend.resolve(if (applications) "corpus/applications" else if (holdout) "corpus/holdout" else "corpus")
  private val projects = ujson.read(Files.readString(corpus.resolve("projects.json"))).arr.toSeq

  private val baseline = ujson.read(Files.readString(corpus.resolve("graph-baseline.json"))).arr.toSeq

  private lazy val analysisSources = {
    val roots = Seq(
      "joern-cli/frontends/dartsrc2cpg/src",
      "joern-cli/frontends/dartsrc2cpg/astgen/lib",
      "joern-cli/frontends/dartsrc2cpg/astgen/bin",
      "joern-cli/frontends/x2cpg/src/main",
      "dataflowengineoss/src/main",
      "semanticcpg/src/main",
      "joern-cli/frontends/dartsrc2cpg/astgen/pubspec.lock",
      "joern-cli/frontends/dartsrc2cpg/astgen/pubspec.yaml",
      "joern-cli/frontends/dartsrc2cpg/build.sbt",
      "joern-cli/frontends/x2cpg/build.sbt",
      "joern-cli/build.sbt",
      "dataflowengineoss/build.sbt",
      "semanticcpg/build.sbt",
      "build.sbt",
      "project/Versions.scala",
      "project/Projects.scala"
    )
    val files = roots
      .flatMap { root =>
        val stream = Files.walk(repository.resolve(root))
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
      }
      .sortBy(path => repository.relativize(path).toString.replace('\\', '/'))
    val digest = MessageDigest.getInstance("SHA-256")
    files.foreach { file =>
      digest.update(repository.relativize(file).toString.replace('\\', '/').getBytes(UTF_8))
      digest.update(0.toByte)
      digest.update(Files.readAllBytes(file))
      digest.update(0.toByte)
    }
    ujson.Obj(
      "algorithm" -> "sha256-path-nul-content-nul",
      "sha256"    -> java.util.HexFormat.of().formatHex(digest.digest()),
      "files"     -> files.size,
      "roots"     -> ujson.Arr.from(roots)
    )
  }

  private def audit(cpg: Cpg, packageName: String): Seq[String] = {
    val errors                                          = scala.collection.mutable.ArrayBuffer.empty[String]
    def check(valid: Boolean, message: => String): Unit = if (!valid) errors += message
    cpg.method.isExternal(false).l.groupBy(_.fullName).foreach { case (name, methods) =>
      check(methods.size == 1, s"Duplicate method: $name")
    }
    cpg.astNode.foreach { node =>
      check(node._astIn.size <= 1, s"Multiple AST parents: ${node.label} ${node.code}")
    }
    cpg.call.foreach { call =>
      val arguments = call.argument.l
      check(arguments.map(_.argumentIndex).distinct.size == arguments.size, s"Duplicate argument index: ${call.code}")
      check(arguments.forall(_.argumentIndex >= 0), s"Negative argument index: ${call.code}")
      call.callee.isExternal(false).foreach { target =>
        val parameters = target.parameter.index.toSet
        check(
          arguments.forall(a => a.argumentIndex == 0 || parameters(a.argumentIndex)),
          s"Argument without parameter: ${call.code} -> ${target.fullName}"
        )
      }
    }
    cpg.identifier.foreach { identifier =>
      check(identifier.refsTo.size <= 1, s"Multiple REF targets: ${identifier.code}")
      identifier.refsTo.foreach { target =>
        check(identifier.name == target.name, s"REF name mismatch: ${identifier.name} -> ${target.name}")
      }
    }
    // Deferred imports expose a compiler-provided loadLibrary function with no source body.
    cpg.method
      .isExternal(true)
      .fullName
      .filter(name => name.startsWith(s"package:$packageName/") && !name.endsWith("#-1:FUNCTION:loadLibrary"))
      .foreach { name =>
        errors += s"Internal method modeled as external: $name"
      }
    cpg.unknown.foreach { node =>
      check(node.parserTypeName == "GenericTypeAlias", s"Lost executable syntax: ${node.parserTypeName}")
    }
    cpg.method.isExternal(false).foreach { method =>
      val declarations = method.ast.isLocal.name.toSet ++ method.parameter.name.toSet
      method.ast.isIdentifier.filter(id => declarations(id.name)).foreach { id =>
        check(id.refsTo.nonEmpty, s"Missing lexical REF: ${method.fullName}: ${id.code}")
      }
    }
    cpg.cfgNode.foreach { node =>
      node.cfgNext.foreach { next =>
        check(node.method == next.method, s"CFG crosses method boundary: ${node.code} -> ${next.code}")
      }
    }
    val methodNames = cpg.method.fullName.toSet
    cpg.methodRef.foreach { ref =>
      check(methodNames(ref.methodFullName), s"Missing method reference target: ${ref.methodFullName}")
    }
    errors.toSeq.distinct
  }

  "Pinned Dart corpus" should {
    projects.foreach { project =>
      val name = s"${project("name").str}-${project("version").str}"
      s"produce consistent, reloadable graphs for $name" in {
        if (!applications && !holdout && !sys.env.contains("DART_CORPUS_TESTS"))
          cancel("Prepare scripts/corpus.py and set DART_CORPUS_TESTS=1")
        val root = repository.resolve(
          if (applications) s"agents/application-corpus/results/$name"
          else if (holdout) s"agents/dart-holdout/$name"
          else s"agents/dart-corpus/$name"
        )
        Files.createDirectories(root)
        val source = project.obj
          .get("checkout")
          .map(p => repository.resolve(s"agents/application-corpus/${p.str}"))
          .getOrElse(root)
        val packageName = project.obj.get("package").map(_.str).getOrElse(project("name").str)
        val output      = root.resolve("cpg.bin")
        val config      = Config(report = root.resolve("coverage.json").toString)
          .withInputPath(source.resolve(project.obj.get("input").map(_.str).getOrElse("lib")).toString)
          .withOutputPath(output.toString)
          .withSchemaValidation(ValidationMode.Enabled)
        val cpg = new DartSrc2Cpg().createCpg(config).get
        try {
          if (applications) {
            val coverage = ujson.read(Files.readString(root.resolve("coverage.json")))
            coverage("parsedFiles").num shouldBe 0
            coverage("partialFiles").num shouldBe 0
            coverage("skippedFiles").num shouldBe 0
          }
          new PostFrontendValidator(cpg, ValidationLevel.V3).createAndApply()
          X2Cpg.applyDefaultOverlays(cpg)
          cpg.method.isExternal(false).size should be > 0
          val started     = System.nanoTime()
          val definitions = cpg.method
            .isExternal(false)
            .map { method =>
              val problem = ReachingDefProblem.create(method)
              method.fullName -> problem.transferFunction
                .asInstanceOf[ReachingDefTransferFunction]
                .gen
                .values
                .map(_.size)
                .sum
            }
            .toMap
          val maxDefinitions = definitions.values.max
          maxDefinitions should be <= 20000
          new OssDataFlow(new OssDataFlowOptions(maxNumberOfDefinitions = 20000)).run(new LayerCreatorContext(cpg))
          Files.writeString(
            root.resolve("dataflow-overlay.json"),
            ujson.write(
              ujson.Obj(
                "elapsedMillis"     -> ujson.Num((System.nanoTime() - started) / 1000000.0),
                "methods"           -> definitions.size,
                "maxDefinitions"    -> maxDefinitions,
                "definitionLimit"   -> 20000,
                "aboveDefaultLimit" -> ujson.Obj.from(definitions.filter(_._2 > 4000).map { case (name, count) =>
                  name -> ujson.Num(count)
                })
              )
            )
          )
        } finally cpg.close()
        val reloaded = Cpg.withStorage(output)
        try {
          reloaded.method.nameExact(project("probe").str).isExternal(false).size should be > 0
          reloaded.call.callee.isExternal(false).size should be > 0
          val probes =
            ujson.read(Files.readString(corpus.resolve("dataflow-probes.json")))(project("name").str).arr.toSeq
          probes should not be empty
          val defaultFlows = CorpusDataflow.audit(reloaded, probes, DefaultSemantics())
          val summaries    = List("async", "worker_manager", "iterable", "bytes")
            .flatMap { name =>
              new FullNameSemanticsParser().parseFile(frontend.resolve(s"dataflow/$name.semantics").toString)
            }
            .map(_.copy(regex = true))
          val flows = CorpusDataflow.audit(reloaded, probes, DefaultSemantics().plus(summaries))
          Files.writeString(
            root.resolve("dataflow-audit.json"),
            ujson.write(
              ujson.Obj(
                "analysisSources" -> analysisSources,
                "source"          -> project,
                "coverage"        -> ujson.read(Files.readString(root.resolve("coverage.json"))),
                "modelFiles"      -> ujson.Obj.from(List("async", "worker_manager", "iterable", "bytes").map { name =>
                  name -> ujson.Str(Files.readString(frontend.resolve(s"dataflow/$name.semantics")))
                }),
                "defaultSemantics" -> defaultFlows,
                "dartSummaries"    -> flows,
                "reachingDefEdges" -> reloaded.cfgNode.map(_._reachingDefIn.size.toLong).sum.toDouble,
                "maxCallDepth"     -> 4
              ),
              indent = 2
            )
          )
          reloaded.metaData.overlays.l should contain("dataflowOss")
          val defaultFailures         = defaultFlows.arr.filterNot(_("passed").bool).map(_("id").str).toSet
          val expectedDefaultFailures = project.obj
            .get("defaultFailures")
            .map(_.arr.map(_.str).toSet)
            .getOrElse(if (project("name").str == "async") Set("error-not-stacktrace") else Set.empty[String])
          defaultFailures shouldBe expectedDefaultFailures
          val errors = audit(reloaded, packageName)
          val report = ujson.Obj(
            "project"      -> name,
            "files"        -> reloaded.file.name.filter(_.endsWith(".dart")).size,
            "methods"      -> reloaded.method.isExternal(false).size,
            "calls"        -> reloaded.call.size,
            "unknown"      -> reloaded.unknown.size,
            "unknownKinds" -> ujson.Obj.from(reloaded.unknown.l.groupBy(_.parserTypeName).map { case (kind, nodes) =>
              kind -> ujson.Num(nodes.size)
            }),
            "unboundIdentifiers" -> ujson.Arr.from(
              reloaded.identifier.filter(_.refsTo.isEmpty).map(n => s"${n.method.fullName}: ${n.name}").toSeq.distinct
            ),
            "externalInternalTargets" -> ujson.Arr.from(
              reloaded.method
                .isExternal(true)
                .fullName
                .filter(name => name.startsWith(s"package:$packageName/") && !name.endsWith("#-1:FUNCTION:loadLibrary"))
                .toSeq
            ),
            "errors" -> ujson.Arr.from(errors)
          )
          Files.writeString(root.resolve("audit.json"), ujson.write(report, indent = 2))
          withClue(errors.take(30).mkString("\n")) { errors shouldBe empty }
          val failedFlows = flows.arr.filterNot(_("passed").bool).map(_("id").str)
          withClue(s"Dataflow failures in $name: ") { failedFlows shouldBe empty }
          val expected = baseline.find(_("project").str == name).get
          expected.obj.foreach { case (key, value) =>
            withClue(s"$name: $key: ") { report(key) shouldBe value }
          }
        } finally reloaded.close()
      }
    }
  }
}
