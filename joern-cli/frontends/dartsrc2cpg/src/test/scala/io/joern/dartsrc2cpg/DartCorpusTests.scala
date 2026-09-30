package io.joern.dartsrc2cpg

import io.joern.x2cpg.X2Cpg
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DartCorpusTests extends AnyWordSpec with Matchers {
  implicit val resolver: ICallResolver = NoResolve
  "Pinned Dart corpus" should {
    Seq("path-1.9.1" -> "normalize", "collection-1.19.1" -> "binarySearch").foreach { case (project, method) =>
      s"produce queryable, reloadable graphs for $project" in {
        if (!sys.env.contains("DART_CORPUS_TESTS")) cancel("Prepare scripts/corpus.py and set DART_CORPUS_TESTS=1")
        val repository = Iterator
          .iterate(Path.of(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
          .takeWhile(_ != null)
          .find(path => Files.exists(path.resolve("project/Projects.scala")))
          .get
        val root   = repository.resolve(s"agents/dart-corpus/$project")
        val output = root.resolve("cpg.bin")
        val config = Config().withInputPath(root.resolve("lib").toString).withOutputPath(output.toString)
        val cpg    = new DartSrc2Cpg().createCpg(config).get
        try {
          X2Cpg.applyDefaultOverlays(cpg)
          cpg.method.nameExact(method).isExternal(false).size should be > 0
          cpg.call.callee.isExternal(false).size should be > 0
        } finally cpg.close()
        val reloaded = Cpg.withStorage(output)
        try {
          reloaded.method.nameExact(method).isExternal(false).size should be > 0
          reloaded.call.callee.isExternal(false).size should be > 0
        } finally reloaded.close()
      }
    }
  }
}
