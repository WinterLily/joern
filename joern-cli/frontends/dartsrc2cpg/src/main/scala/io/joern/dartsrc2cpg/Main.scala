package io.joern.dartsrc2cpg

import io.joern.x2cpg.{X2CpgConfig, X2CpgMain}
import scopt.OParser

final case class Config(
  exporter: String = Config.defaultExporter,
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
      opt[String]("dart-sdk").action((value, config) => config.copy(sdk = value)).text("Dart 3.9.2 SDK directory"),
      opt[String]("dart-astgen")
        .action((value, config) => config.copy(exporter = value))
        .text("Native analyzer exporter")
    )
  }
}
