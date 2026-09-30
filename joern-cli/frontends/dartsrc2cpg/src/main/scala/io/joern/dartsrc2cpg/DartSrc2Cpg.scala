package io.joern.dartsrc2cpg

import io.joern.x2cpg.{SourceFiles, X2Cpg, X2CpgFrontend}
import io.joern.x2cpg.frontendspecific.DartLanguage
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.Cpg
import java.nio.file.{Files, Paths}
import org.slf4j.LoggerFactory
import scala.util.Try
import ujson.Value

class DartSrc2Cpg extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig = Config()

  override def createCpg(config: Config): Try[Cpg] = Try {
    val started = System.nanoTime()
    val input   = Paths.get(config.inputPath).toAbsolutePath.normalize()
    require(Files.exists(input), s"Dart input does not exist: $input")
    val root =
      if (Files.isDirectory(input)) input
      else {
        Iterator
          .iterate(input.getParent)(_.getParent)
          .takeWhile(_ != null)
          .find(path => Files.exists(path.resolve("pubspec.yaml")))
          .getOrElse(input.getParent)
      }
    require(config.sdk.nonEmpty, "Set DART_SDK or --dart-sdk to a Dart 3.9.2 SDK")
    val records  = ExporterRunner.run(config, root, input)
    val exported = ExportProtocol.units(records)
    val units    = exported.filter { unit =>
      val file = root.resolve(java.net.URI.create(unit("file").str).getPath).toString
      SourceFiles.filterFile(
        file,
        config.inputPath,
        Some(config.defaultIgnoredFilesRegex),
        Some(config.ignoredFilesRegex),
        Some(config.ignoredFiles)
      )
    }
    val cpg = X2Cpg
      .withNewEmptyCpg(config.outputPath, config) { (cpg, _) =>
        new MetaDataPass(cpg, DartLanguage.Name, root.toString).createAndApply()
        new AstCreationPass(cpg, units, config).createAndApply()
        new MethodReferencePass(cpg, units).createAndApply()
        TypeNodePass.withTypesFromCpg(cpg).createAndApply()
      }
      .get
    try {
      val nodes  = units.flatMap(_("nodes").arr)
      val report = ujson.Obj(
        "exporter"              -> records.head,
        "exportedFiles"         -> exported.size,
        "includedFiles"         -> units.size,
        "skippedFiles"          -> (exported.size - units.size),
        "parsedFiles"           -> units.count(_("status").str == "parsed"),
        "partialFiles"          -> units.count(_("status").str == "partial"),
        "unsupportedConstructs" -> nodes.count(_("kind").str == "Unsupported"),
        "unresolvedCalls"       -> nodes.count(n =>
          Set("MethodInvocation", "FunctionExpressionInvocation", "InstanceCreationExpression").contains(
            n("kind").str
          ) && n.obj.get("target").forall(_ == ujson.Null)
        ),
        "elapsedMillis"        -> ((System.nanoTime() - started) / 1000000),
        "exporterPeakRssBytes" -> records.last.obj.getOrElse("peakRssBytes", ujson.Null)
      )
      LoggerFactory.getLogger(getClass).info(s"Dart scan: ${ujson.write(report)}")
      if (config.report.nonEmpty) Files.writeString(Paths.get(config.report), ujson.write(report, indent = 2))
      cpg
    } catch {
      case error: Exception =>
        cpg.close()
        throw error
    }
  }
}

private[dartsrc2cpg] object ExportProtocol {
  def units(records: Seq[Value]): Seq[Value] = {
    require(records.nonEmpty, "Empty Dart exporter output")
    val header = records.head
    require(
      header("record").str == "header" && header("protocolVersion").num == 1 &&
        header("offsetEncoding").str == "utf-16" && header("analyzerVersion").str == "8.4.1" &&
        header("sdkVersion").str == "3.9.2" && header.obj.get("exporterVersion").contains(ujson.Str("0.3.5")),
      "Incompatible Dart exporter protocol"
    )
    require(records.last("record").str == "summary", "Truncated Dart exporter output")
    val units = records.slice(1, records.size - 1)
    require(
      units.forall(_("record").str == "unit") && records.last("files").num == units.size,
      "Invalid Dart exporter unit count"
    )
    val files = units.map { unit =>
      val uri  = java.net.URI.create(unit("file").str)
      val path = Paths.get(uri.getPath)
      require(
        !uri.isAbsolute && uri.getAuthority == null && uri.getQuery == null && uri.getFragment == null &&
          !path.isAbsolute && !path.normalize().startsWith("..") && path.toString.endsWith(".dart"),
        "Invalid Dart unit path"
      )
      require(Set("resolved", "partial", "parsed").contains(unit("status").str), "Invalid Dart unit status")
      unit("source").str
      Seq("nodes", "symbols", "diagnostics", "unsupportedKinds").foreach(key => unit(key).arr)
      path.normalize().toString
    }
    require(files.distinct.size == files.size, "Duplicate Dart exporter units")
    units
  }
}
