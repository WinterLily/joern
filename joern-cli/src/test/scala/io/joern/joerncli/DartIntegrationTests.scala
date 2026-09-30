package io.joern.joerncli

import io.joern.console.{Console, ConsoleConfig, FrontendConfig, InstallConfig}
import io.joern.console.cpgcreation.{CpgGeneratorFactory, DartCpgGenerator, cpgGeneratorForLanguage, guessLanguage}
import io.joern.console.testing.TestWorkspaceLoader
import io.joern.console.defaultAvailableWidthProvider
import io.joern.console.workspacehandling.Project
import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.EngineContext
import io.joern.dataflowengineoss.layers.dataflows.{OssDataFlow, OssDataFlowOptions}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import io.shiftleft.semanticcpg.utils.ExternalCommand

class DartIntegrationTests extends AnyWordSpec with Matchers {
  implicit val context: EngineContext = EngineContext()

  "Dart integration" should {
    "detect Dart and select its generator explicitly" in {
      guessLanguage("main.dart") shouldBe Some("DART")
      cpgGeneratorForLanguage("DART", FrontendConfig(), Paths.get("."), Nil).get shouldBe a[DartCpgGenerator]
      new CpgGeneratorFactory(new ConsoleConfig()).languageIsKnown("DART") shouldBe true
    }

    "import the acceptance package through staged CLI and console" in {
      val stage =
        Paths.get(sys.env.getOrElse("DART_FRONTEND_STAGE", cancel("Set DART_FRONTEND_STAGE after dartsrc2cpg/stage")))
      val repository = Iterator
        .iterate(Paths.get("").toAbsolutePath)(_.getParent)
        .takeWhile(_ != null)
        .find(path => Files.isRegularFile(path.resolve("project/Projects.scala")))
        .get
      val dir = Files.createTempDirectory(Files.createDirectories(repository.resolve("agents")), "dart-integration-")
      try {
        val install  = Files.createDirectories(dir.resolve("install/frontends")).getParent
        val frontend = install.resolve("frontends/dartsrc2cpg")
        val paths    = Files.walk(stage)
        try
          paths.iterator().asScala.foreach { path =>
            val target = frontend.resolve(stage.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(target)
            else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
          }
        finally paths.close()
        val launcher = if (scala.util.Properties.isWin) "dartsrc2cpg.bat" else "dartsrc2cpg"
        val wrapper  = install.resolve(launcher)
        Files.copy(repository.resolve(s"joern-cli/src/universal/$launcher"), wrapper)
        wrapper.toFile.setExecutable(true)
        val source = repository.resolve("joern-cli/frontends/dartsrc2cpg/src/test/resources/dataflow")
        guessLanguage(source.toString) shouldBe Some("DART")
        val installConfig = new InstallConfig(Map("SHIFTLEFT_OCULAR_INSTALL_DIR" -> install.toString))
        val output        = dir.resolve("cli.bin")
        val classpath     = Iterator
          .iterate(getClass.getClassLoader)(_.getParent)
          .takeWhile(_ != null)
          .collect { case loader: java.net.URLClassLoader => loader.getURLs.toSeq }
          .flatten
          .map(url => Paths.get(url.toURI).toString)
          .toSeq
          .distinct
          .mkString(java.io.File.pathSeparator)
        val javaCommand = Paths.get(System.getProperty("java.home"), "bin", "java").toString
        ExternalCommand
          .run(
            Seq(
              javaCommand,
              "-cp",
              classpath,
              "io.joern.joerncli.JoernParse",
              source.toString,
              "--language",
              "dart",
              "-o",
              output.toString
            ),
            workingDir = Some(dir),
            extraEnv = Map("SHIFTLEFT_OCULAR_INSTALL_DIR" -> install.toString)
          )
          .toTry
          .get
        val cpg = Cpg.withStorage(output)
        try assertFlow(cpg)
        finally cpg.close()
        val console = new Console[Project](TestWorkspaceLoader, dir) {
          override def config = new ConsoleConfig(installConfig)
        }
        try {
          val imported = console.importCode.dart(source.toString)
          new OssDataFlow(new OssDataFlowOptions()).run(new LayerCreatorContext(imported))
          assertFlow(imported)
        } finally console.cpgs.foreach(_.close())
      } finally FileUtil.delete(dir)
    }
  }

  private def assertFlow(cpg: Cpg): Unit = {
    cpg.metaData.language.l shouldBe List("DART")
    cpg.call.nameExact("sink").argument.reachableByFlows(cpg.identifier.nameExact("input")).size should be > 0
  }
}
