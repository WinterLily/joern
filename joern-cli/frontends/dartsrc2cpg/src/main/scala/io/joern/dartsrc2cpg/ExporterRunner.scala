package io.joern.dartsrc2cpg

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import ujson.Value

private[dartsrc2cpg] object ExporterRunner {
  def run(config: Config, root: Path, input: Path): Seq[Value] = {
    val sdk = Path.of(config.sdk)
    require(
      Files.isDirectory(sdk.resolve("lib")) && Files.isRegularFile(sdk.resolve("version")),
      s"Not a Dart SDK: $sdk"
    )
    require(Files.readString(sdk.resolve("version")).trim == "3.9.2", "Expected Dart SDK 3.9.2")
    val output = execute(
      Seq(config.exporter, root.toString, input.toString, config.sdk, "--metrics") ++
        (if (config.environment == "analyzer-default") Nil else Seq(s"--environment=${config.environment}")),
      config.exporterTimeoutSeconds,
      config.exporterMaxOutputBytes
    )
    parse(output)
  }

  private[dartsrc2cpg] def parse(output: String): Seq[Value] = {
    try {
      val records = output.linesIterator.zipWithIndex.map { case (line, index) =>
        try ujson.read(line)
        catch {
          case NonFatal(error) =>
            throw new IllegalArgumentException(s"Malformed Dart exporter JSON at line ${index + 1}", error)
        }
      }.toVector
      ExportProtocol.units(records)
      records
    } catch {
      case error: IllegalArgumentException => throw error
      case NonFatal(error)                 =>
        throw new IllegalArgumentException("Malformed Dart exporter protocol: " + error.getMessage, error)
    }
  }

  private[dartsrc2cpg] def execute(command: Seq[String], timeoutSeconds: Int, maxOutputBytes: Long): String = {
    require(timeoutSeconds > 0 && maxOutputBytes > 0, "Dart exporter resource limits must be positive")
    val process = try new ProcessBuilder(command.asJava).start()
    catch {
      case NonFatal(error) =>
        throw new IllegalArgumentException(s"Cannot start Dart exporter '${command.head}': ${error.getMessage}", error)
    }
    process.getOutputStream.close()
    val readers                                                           = Executors.newFixedThreadPool(2)
    def read(stream: InputStream, limit: Long, truncate: Boolean): String = {
      val result = new ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      var count  = stream.read(buffer)
      while (count != -1) {
        val remaining = limit - result.size()
        if (count > remaining && !truncate) {
          process.destroyForcibly()
          throw new IllegalArgumentException(s"Dart exporter output exceeds $limit bytes")
        }
        result.write(buffer, 0, math.min(count.toLong, remaining).toInt)
        count = stream.read(buffer)
      }
      result.toString(UTF_8)
    }
    val stdout = readers.submit(new Callable[String] {
      override def call(): String = read(process.getInputStream, maxOutputBytes, false)
    })
    val stderr = readers.submit(new Callable[String] {
      override def call(): String = read(process.getErrorStream, 65536, true)
    })
    try {
      require(
        process.waitFor(timeoutSeconds.toLong, TimeUnit.SECONDS),
        s"Dart exporter timed out after $timeoutSeconds seconds"
      )
      val output = try stdout.get(5, TimeUnit.SECONDS)
      catch { case error: java.util.concurrent.ExecutionException => throw error.getCause }
      val errors = stderr.get(5, TimeUnit.SECONDS)
      require(process.exitValue() == 0, s"Dart exporter exited with code ${process.exitValue()}: $errors")
      output
    } finally {
      process.descendants().forEach(_.destroyForcibly())
      process.destroyForcibly()
      process.getInputStream.close()
      process.getErrorStream.close()
      readers.shutdownNow()
    }
  }
}
