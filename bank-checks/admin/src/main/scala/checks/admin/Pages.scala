package checks.admin

import zio.*
import zio.http.*

// The wizard: one page and its module, from this module's resources.
object Pages:
  private def resource(name: String, mediaType: MediaType): Handler[Any, Nothing, Any, Response] =
    Handler.fromZIO(
      ZIO
        .attemptBlocking(Option(getClass.getResourceAsStream(s"/pages/$name")).map(_.readAllBytes()))
        .orDie
        .map {
          case Some(bytes) => Response(body = Body.fromArray(bytes)).contentType(mediaType)
          case None        => Response.notFound
        }
    )

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / ""                    -> resource("index.html", MediaType.text.html),
    Method.GET / "pages" / "wizard.js" -> resource("wizard.js", MediaType.text.javascript),
    Method.GET / "pages" / "style.css" -> resource("style.css", MediaType.text.css)
  )
