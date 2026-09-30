package io.joern.dartsrc2cpg

import io.joern.x2cpg.{SourceFiles, X2Cpg, X2CpgFrontend}
import io.joern.x2cpg.frontendspecific.DartLanguage
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.Cpg
import java.nio.file.{Files, Paths}
import io.shiftleft.semanticcpg.utils.ExternalCommand
import scala.util.Try
import ujson.Value

class DartSrc2Cpg extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig = Config()

  override def createCpg(config: Config): Try[Cpg] = Try {
    val input = Paths.get(config.inputPath).toAbsolutePath.normalize()
    val root  =
      if (Files.isDirectory(input)) input
      else {
        Iterator
          .iterate(input.getParent)(_.getParent)
          .takeWhile(_ != null)
          .find(path => Files.exists(path.resolve("pubspec.yaml")))
          .getOrElse(input.getParent)
      }
    require(config.sdk.nonEmpty, "Set DART_SDK or --dart-sdk to a Dart 3.9.2 SDK")
    val records =
      ExternalCommand.run(Seq(config.exporter, root.toString, input.toString, config.sdk)).toTry.get.map(ujson.read(_))
    val units = ExportProtocol.units(records).filter { unit =>
      val file = root.resolve(java.net.URI.create(unit("file").str).getPath).toString
      SourceFiles.filterFile(
        file,
        config.inputPath,
        Some(config.defaultIgnoredFilesRegex),
        Some(config.ignoredFilesRegex),
        Some(config.ignoredFiles)
      )
    }
    X2Cpg
      .withNewEmptyCpg(config.outputPath, config) { (cpg, _) =>
        new MetaDataPass(cpg, DartLanguage.Name, root.toString).createAndApply()
        new AstCreationPass(cpg, units, config).createAndApply()
        TypeNodePass.withTypesFromCpg(cpg).createAndApply()
      }
      .get
  }
}

private[dartsrc2cpg] object ExportProtocol {
  def units(records: Seq[Value]): Seq[Value] = {
    require(records.nonEmpty, "Empty Dart exporter output")
    val header = records.head
    require(
      header("record").str == "header" && header("protocolVersion").num == 1 &&
        header("offsetEncoding").str == "utf-16" && header("analyzerVersion").str == "8.4.1" &&
        header("sdkVersion").str == "3.9.2",
      "Incompatible Dart exporter protocol"
    )
    require(records.last("record").str == "summary", "Truncated Dart exporter output")
    val units = records.slice(1, records.size - 1)
    require(
      units.forall(_("record").str == "unit") && records.last("files").num == units.size,
      "Invalid Dart exporter unit count"
    )
    units
  }
}
