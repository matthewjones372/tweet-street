package checks.platform

import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

enum RegistryError(msg: String) extends RuntimeException(msg):
  case Refused(artifact: String, why: String) extends RegistryError(s"the registry refused $artifact: $why")
  case Failed(artifact: String, status: Int, body: String)
      extends RegistryError(s"registering $artifact: $status $body")
  case Unreachable(cause: Throwable) extends RegistryError(s"the registry did not answer: ${cause.getMessage}")

final case class Registered(artifact: String, version: String, contentId: Int)

// Apicurio's REST API, v3, as lark-bank speaks it: a Protobuf schema registered once, found after, and held to
// FULL_TRANSITIVE from its first version.
final case class Apicurio(url: String, group: String = "default"):
  private val http = HttpClient.newHttpClient()

  def register(artifact: String, content: String, references: List[Json] = Nil): IO[RegistryError, Registered] =
    val body = Json.Obj(
      "artifactId"   -> Json.Str(artifact),
      "artifactType" -> Json.Str("PROTOBUF"),
      "firstVersion" -> Json.Obj(
        "content" -> Json.Obj(
          "content"     -> Json.Str(content),
          "contentType" -> Json.Str("application/x-protobuf"),
          "references"  -> Json.Arr(Chunk.fromIterable(references))
        )
      )
    )
    for
      created <- send(s"/groups/$group/artifacts?ifExists=FIND_OR_CREATE_VERSION", body.toJson)
      _       <- ZIO.when(created.statusCode == 400 || created.statusCode == 409)(
             ZIO.fail(RegistryError.Refused(artifact, created.body))
           )
      _ <- ZIO.when(created.statusCode / 100 != 2)(
             ZIO.fail(RegistryError.Failed(artifact, created.statusCode, created.body))
           )
      version <- ZIO
                   .fromEither(created.body.fromJson[Json].flatMap(_.get(zio.json.ast.JsonCursor.field("version"))))
                   .mapError(why => RegistryError.Failed(artifact, created.statusCode, why))
      rule = Json.Obj("ruleType" -> Json.Str("COMPATIBILITY"), "config" -> Json.Str("FULL_TRANSITIVE"))
      set <- send(s"/groups/$group/artifacts/$artifact/rules", rule.toJson)
      _   <- ZIO.when(set.statusCode / 100 != 2 && set.statusCode != 409)(
             ZIO.fail(RegistryError.Failed(artifact, set.statusCode, set.body))
           )
    yield Registered(artifact, text(version, "version"), number(version, "contentId"))

  def reference(registered: Registered, name: String): Json =
    Json.Obj(
      "groupId"    -> Json.Str(group),
      "artifactId" -> Json.Str(registered.artifact),
      "version"    -> Json.Str(registered.version),
      "name"       -> Json.Str(name)
    )

  private def text(json: Json, field: String) = json match
    case Json.Obj(fields) => fields.collectFirst { case (`field`, Json.Str(value)) => value }.getOrElse("")
    case _                => ""

  private def number(json: Json, field: String) = json match
    case Json.Obj(fields) => fields.collectFirst { case (`field`, Json.Num(value)) => value.intValue }.getOrElse(0)
    case _                => 0

  private def send(path: String, body: String): IO[RegistryError, HttpResponse[String]] =
    ZIO
      .fromCompletableFuture(
        http.sendAsync(
          HttpRequest
            .newBuilder(URI.create(url.stripSuffix("/") + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
      .mapError(RegistryError.Unreachable(_))
