package io.joern.dartsrc2cpg

import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.FullNameSemanticsParser
import io.shiftleft.codepropertygraph.generated.Cpg
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class DartWitnessAuditTests extends AnyWordSpec with Matchers {
  "Saved Dart corpus witnesses" should {
    "retain bounded alternatives with graph and query provenance" in {
      if (!sys.env.contains("DART_WITNESS_AUDIT_TESTS"))
        cancel("Set DART_WITNESS_AUDIT_TESTS to audit prepared corpus graphs")
      val repository = Iterator
        .iterate(Path.of(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
        .takeWhile(_ != null)
        .find(path => Files.exists(path.resolve("project/Projects.scala")))
        .get
      val frontend = repository.resolve("joern-cli/frontends/dartsrc2cpg")
      val category = sys.env.getOrElse("DART_WITNESS_CATEGORY", "packages")
      val bound    = sys.env.getOrElse("DART_WITNESS_BOUND", "4").toInt
      val rounds   = sys.env.getOrElse("DART_WITNESS_HELD_ITERATIONS", "0").toInt
      require(bound > 1, "Alternative audits require a bound greater than one")
      val corpus = frontend.resolve(category match {
        case "packages"     => "corpus"
        case "applications" => "corpus/applications"
        case "holdout"      => "corpus/holdout"
      })
      val scratch = repository.resolve(category match {
        case "packages"     => "agents/dart-corpus"
        case "applications" => "agents/application-corpus/results"
        case "holdout"      => "agents/dart-holdout"
      })
      val probesByProject = ujson.read(Files.readString(corpus.resolve("dataflow-probes.json")))
      val projects        = ujson.read(Files.readString(corpus.resolve("projects.json"))).arr.filter { project =>
        sys.env.get("DART_WITNESS_PROJECT").forall(_ == project("name").str)
      }
      projects should not be empty
      projects.foreach { project =>
        val name      = project("name").str
        val directory = scratch.resolve(s"$name-${project("version").str}")
        val previous  = ujson.read(Files.readString(directory.resolve("dataflow-audit.json")))
        val probes    = probesByProject(name).arr.toSeq.map { original =>
          val probe = ujson.read(ujson.write(original))
          probe("maxWitnessesPerEndpoint") = bound
          probe("maxHeldTaskIterations") = rounds
          probe
        }
        val cpg = Cpg.withStorage(directory.resolve("cpg.bin"))
        try {
          val stock     = CorpusDataflow.audit(cpg, probes, DefaultSemantics())
          val summaries = List("async", "worker_manager", "iterable", "bytes")
            .flatMap { name =>
              new FullNameSemanticsParser().parseFile(frontend.resolve(s"dataflow/$name.semantics").toString)
            }
            .map(_.copy(regex = true))
          val modeled = CorpusDataflow.audit(cpg, probes, DefaultSemantics().plus(summaries))
          val report  = ujson.Obj(
            "source"                  -> previous("source"),
            "graphAnalysisSources"    -> previous("analysisSources"),
            "exporter"                -> previous("coverage")("exporter"),
            "modelFiles"              -> previous("modelFiles"),
            "maxWitnessesPerEndpoint" -> bound,
            "maxHeldTaskIterations"   -> rounds,
            "defaultSemantics"        -> stock,
            "dartSummaries"           -> modeled,
            "semanticReview" -> "pending; bounded alternatives do not establish route feasibility or exhaustiveness"
          )
          Files.writeString(directory.resolve("alternative-audit.json"), ujson.write(report, indent = 2))
          for ((legacy, current) <- previous("dartSummaries").arr.zip(modeled.arr)) {
            withClue(s"$name ${current("id").str}") {
              current("sources") shouldBe legacy("sources")
              current("sinks") shouldBe legacy("sinks")
              current("passed") shouldBe legacy("passed")
              current("omittedWitnesses").num shouldBe 0
              current("omittedDetailedWitnesses").num shouldBe 0
            }
          }
        } finally cpg.close()
      }
    }
  }
}
