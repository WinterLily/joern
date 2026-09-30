import sbt.BareBuildSyntax.dependsOn

name := "dartsrc2cpg"
dependsOn(
  Projects.x2cpg % "compile->compile;test->test",
  Projects.dataflowengineoss % "test->test",
  Projects.linterRules % ScalafixConfig
)
libraryDependencies ++= Seq(
  "io.shiftleft" %% "codepropertygraph" % Versions.cpg,
  "com.lihaoyi" %% "ujson" % Versions.upickle,
  "org.scalatest" %% "scalatest" % Versions.scalatest % Test
)
enablePlugins(JavaAppPackaging, LauncherJarPlugin)
Universal / topLevelDirectory := None
Universal / mappings += {
  val binary = if (scala.util.Properties.isWin) "dart_astgen.exe" else "dart_astgen"
  val executable = baseDirectory.value / "bin" / binary
  require(executable.exists, "Build the exporter first: dart compile exe astgen/bin/dart_astgen.dart -o bin/dart_astgen")
  fileConverter.value.toVirtualFile(executable.toPath) -> s"bin/$binary"
}
