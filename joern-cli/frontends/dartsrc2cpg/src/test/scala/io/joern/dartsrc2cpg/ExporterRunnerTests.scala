package io.joern.dartsrc2cpg

import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ExporterRunnerTests extends AnyWordSpec with Matchers {
  private val java                               = Path.of(System.getProperty("java.home"), "bin", "java").toString
  private def fixture(check: Path => Unit): Unit = {
    val repository = Iterator
      .iterate(Path.of(sys.env.getOrElse("DART_TEST_REPOSITORY", "")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.exists(path.resolve("project/Projects.scala")))
      .get
    val dir = Files.createTempDirectory(Files.createDirectories(repository.resolve("agents")), "runner space λ ")
    try {
      val script = dir.resolve("Exporter.java")
      Files.writeString(
        script,
        """class Exporter {
        public static void main(String[] args) throws Exception {
          switch(args[0]) {
            case "timeout": Thread.sleep(30000); break;
            case "crash": System.err.print("fixture crash"); System.exit(7); break;
            case "flood": for(int i=0;i<100000;i++) System.out.print("xxxxxxxx"); break;
            case "stderr": for(int i=0;i<100000;i++) System.err.print("xxxxxxxx"); System.out.print("ok"); break;
            default: System.out.print("λ space");
          }
        }
      }"""
      )
      check(script)
    } finally io.shiftleft.semanticcpg.utils.FileUtil.delete(dir)
  }
  "Dart exporter runner" should {
    "reject malformed, incompatible and unsafe records" in {
      val header = ujson.Obj(
        "record"          -> "header",
        "protocolVersion" -> 1,
        "exporterVersion" -> "0.3.5",
        "sdkVersion"      -> "3.9.2",
        "analyzerVersion" -> "8.4.1",
        "offsetEncoding"  -> "utf-16"
      )
      val summary                               = ujson.Obj("record" -> "summary", "files" -> 0)
      def stream(records: ujson.Value*): String = records.map(ujson.write(_)).mkString("\n")
      ExporterRunner.parse(stream(header, summary)).size shouldBe 2
      intercept[IllegalArgumentException](ExporterRunner.parse("not json")).getMessage should include("line 1")
      intercept[IllegalArgumentException](ExporterRunner.parse("{}"))
      intercept[IllegalArgumentException](ExporterRunner.parse(stream(header)))
      header("exporterVersion") = "999"
      intercept[IllegalArgumentException](ExporterRunner.parse(stream(header, summary)))
      header("exporterVersion") = "0.3.5"
      val unit = ujson.Obj("record" -> "unit", "file" -> "../outside.dart")
      summary("files") = 1
      intercept[IllegalArgumentException](
        ExporterRunner.parse(stream(header, unit, summary))
      ).getMessage should include("unit path")
    }
    "report missing executables" in {
      intercept[IllegalArgumentException](
        ExporterRunner.execute(Seq("missing-dart-exporter-xyz"), 5, 1024)
      ).getMessage should include("Cannot start Dart exporter")
    }
    "preserve Unicode and spaced arguments while draining stderr" in fixture { script =>
      ExporterRunner.execute(Seq(java, script.toString, "ok"), 30, 1024) shouldBe "λ space"
      ExporterRunner.execute(Seq(java, script.toString, "stderr"), 30, 1024) shouldBe "ok"
    }
    "report crashes, timeouts and excessive output" in fixture { script =>
      intercept[IllegalArgumentException](
        ExporterRunner.execute(Seq(java, script.toString, "crash"), 30, 1024)
      ).getMessage should include("code 7: fixture crash")
      intercept[IllegalArgumentException](
        ExporterRunner.execute(Seq(java, script.toString, "timeout"), 2, 1024)
      ).getMessage should include("timed out")
      intercept[IllegalArgumentException](
        ExporterRunner.execute(Seq(java, script.toString, "flood"), 30, 1024)
      ).getMessage should include("exceeds 1024 bytes")
    }
    "reject invalid SDKs before launching the exporter" in {
      intercept[IllegalArgumentException](
        ExporterRunner.run(Config(sdk = "missing-sdk"), Path.of("."), Path.of("."))
      ).getMessage should include("Not a Dart SDK")
    }
  }
}
