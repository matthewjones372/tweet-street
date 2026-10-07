package checks.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import checks.platform.TestPostgres
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import java.io.{ByteArrayOutputStream, PrintStream}

/**
 * lark-bank spec 0023's `log-json`: every line bank-checks writes is one JSON
 * object, with the estate's field names.
 */
object LogJsonSpec extends ZIOSpecDefault:
  private def configure(name: String): Unit =
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    context.reset()
    val configurator = JoranConfigurator()
    configurator.setContext(context)
    configurator.doConfigure(getClass.getClassLoader.getResource(name))

  /**
   * What [[work]] wrote to stdout under the app's own `logback.xml`, the tests'
   * configuration put back after. Other specs log while this one runs, so
   * stdout is taken only while `logback.xml` is in force, never under the
   * tests' plain lines.
   */
  private def written[E, A](work: ZIO[Any, E, A]): ZIO[Any, E, List[String]] =
    val out    = ByteArrayOutputStream()
    val before = java.lang.System.out
    ZIO
      .acquireReleaseWith(ZIO.succeed { configure("logback.xml"); java.lang.System.setOut(PrintStream(out, true)) })(
        _ => ZIO.succeed { java.lang.System.setOut(before); configure("logback-test.xml") }
      )(_ => work.provideLayer(Runtime.removeDefaultLoggers >>> SLF4J.slf4j))
      .as(out.toString("UTF-8").linesIterator.filter(_.trim.nonEmpty).toList)

  def spec = suite("LogJson")(
    test("the app's lines, and its libraries', are JSON objects, and an annotation is a field of its own") {
      val json = ObjectMapper()
      for
        database <- TestPostgres.fresh
        lines    <- written(
                   Migrations.run(database) *>
                     ZIO.logAnnotate("transfer_id", "t-1")(ZIO.logWarning("a line about one transfer"))
                 )
        parsed = lines.map(json.readTree)
        line   = parsed.find(_.path("msg").asText == "a line about one transfer")
      yield assertTrue(
        lines.nonEmpty,
        parsed.forall(l => List("ts", "level", "service", "msg").forall(f => l.path(f).asText.nonEmpty)),
        parsed.forall(_.path("service").asText == "bank-checks"),
        line.exists(_.path("level").asText == "WARN"),
        line.exists(_.path("transfer_id").asText == "t-1")
      )
    }
  ) @@ TestAspect.sequential
