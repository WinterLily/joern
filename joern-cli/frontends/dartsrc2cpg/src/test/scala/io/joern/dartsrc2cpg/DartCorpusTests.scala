package io.joern.dartsrc2cpg

import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.validation.{PostFrontendValidator, ValidationLevel}
import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DartCorpusTests extends AnyWordSpec with Matchers {
  implicit val resolver: ICallResolver = NoResolve
  private val repository               = Iterator
    .iterate(Path.of(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.exists(path.resolve("project/Projects.scala")))
    .get
  private val frontend = repository.resolve("joern-cli/frontends/dartsrc2cpg")
  private val projects = ujson.read(Files.readString(frontend.resolve("corpus/projects.json"))).arr.toSeq

  private val baseline = ujson.read(Files.readString(frontend.resolve("corpus/graph-baseline.json"))).arr.toSeq

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
    cpg.method.isExternal(true).fullName.filter(_.startsWith(s"package:$packageName/")).foreach { name =>
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
        if (!sys.env.contains("DART_CORPUS_TESTS")) cancel("Prepare scripts/corpus.py and set DART_CORPUS_TESTS=1")
        val root   = repository.resolve(s"agents/dart-corpus/$name")
        val output = root.resolve("cpg.bin")
        val config = Config()
          .withInputPath(root.resolve("lib").toString)
          .withOutputPath(output.toString)
          .withSchemaValidation(ValidationMode.Enabled)
        val cpg = new DartSrc2Cpg().createCpg(config).get
        try {
          new PostFrontendValidator(cpg, ValidationLevel.V3).createAndApply()
          X2Cpg.applyDefaultOverlays(cpg)
          cpg.method.isExternal(false).size should be > 0
        } finally cpg.close()
        val reloaded = Cpg.withStorage(output)
        try {
          reloaded.method.nameExact(project("probe").str).isExternal(false).size should be > 0
          reloaded.call.callee.isExternal(false).size should be > 0
          val errors = audit(reloaded, project("name").str)
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
              reloaded.method.isExternal(true).fullName.filter(_.startsWith(s"package:${project("name").str}/")).toSeq
            ),
            "errors" -> ujson.Arr.from(errors)
          )
          Files.writeString(root.resolve("audit.json"), ujson.write(report, indent = 2))
          withClue(errors.take(30).mkString("\n")) { errors shouldBe empty }
          val expected = baseline.find(_("project").str == name).get
          expected.obj.foreach { case (key, value) =>
            withClue(s"$name: $key: ") { report(key) shouldBe value }
          }
        } finally reloaded.close()
      }
    }
  }
}
