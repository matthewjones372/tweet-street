package checks.app

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

// The document the check serves, committed so lark-bank can hold its client to it (bank spec 0018).
object OpenApiSpec extends ZIOSpecDefault:
  private val committed = Path.of("..").toAbsolutePath.normalize.resolve("openapi.json")

  def spec = suite("The OpenAPI document")(
    test("the committed openapi.json is the one the check serves") {
      val served = Main.openApi.toJsonPretty
      // -Dchecks.regenerate=true writes it instead, after an endpoint changes.
      if sys.props.get("checks.regenerate").contains("true") then Files.writeString(committed, served): Unit
      assertTrue(Files.exists(committed) && Files.readString(committed) == served)
    }
  )
