package io.joern.dartsrc2cpg

import io.joern.x2cpg.{X2CpgConfig, X2CpgMain}
import scopt.OParser

final case class Config(
  exporter: String = Config.defaultExporter,
  exporterTimeoutSeconds: Int = 300,
  exporterMaxOutputBytes: Long = 256L * 1024 * 1024,
  report: String = "",
  sdk: String = sys.env.getOrElse("DART_SDK", ""),
  override val genericConfig: X2CpgConfig.GenericConfig = X2CpgConfig.GenericConfig()
) extends X2CpgConfig[Config] {
  override def withGenericConfig(value: X2CpgConfig.GenericConfig): Config = copy(genericConfig = value)
}

object Config {
  def defaultExporter: String = sys.env.getOrElse(
    "DART_ASTGEN", {
      val name     = if (scala.util.Properties.isWin) "dart_astgen.exe" else "dart_astgen"
      val location = java.nio.file.Paths.get(classOf[DartSrc2Cpg].getProtectionDomain.getCodeSource.getLocation.toURI)
      val staged   = location.getParent.getParent.resolve("bin").resolve(name)
      if (java.nio.file.Files.isExecutable(staged)) staged.toString else name
    }
  )
}

object Main extends X2CpgMain(new DartSrc2Cpg, Frontend.parser)

object Frontend {
  val parser: OParser[Unit, Config] = {
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      programName("dartsrc2cpg"),
      opt[Int]("dart-timeout")
        .validate(value => if (value > 0) success else failure("Timeout must be positive"))
        .action((value, config) => config.copy(exporterTimeoutSeconds = value))
        .text("Exporter timeout in seconds (default: 300)"),
      opt[Long]("dart-max-output-bytes")
        .validate(value => if (value > 0) success else failure("Output limit must be positive"))
        .action((value, config) => config.copy(exporterMaxOutputBytes = value))
        .text("Maximum exporter stdout bytes (default: 268435456)"),
      opt[String]("dart-report")
        .action((value, config) => config.copy(report = value))
        .text("Write scan coverage and resource metrics as JSON"),
      opt[String]("dart-sdk").action((value, config) => config.copy(sdk = value)).text("Dart 3.9.2 SDK directory"),
      opt[String]("dart-astgen")
        .action((value, config) => config.copy(exporter = value))
        .text("Native analyzer exporter")
    )
  }
}
