package checks.policy.adapters

import checks.policy.domain.{PolicyError, Proposal}
import checks.policy.service.Approvals
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

// Where Approvals is, and how the check gets a token for it: the form it posts to the identity provider's token
// endpoint, client credentials at home and the test grant in Docker, and the client's secret, kept apart from the form.
final case class ApprovalsConfig(url: String, tokenUrl: String, tokenForm: String, clientSecret: String = "")

object ApprovalsHttp:
  val layer: URLayer[ApprovalsConfig & Client, Approvals] = ZLayer.fromFunction(ApprovalsHttpLive.apply)

final private case class ApprovalsHttpLive(config: ApprovalsConfig, client: Client) extends Approvals:
  private def failed(why: String) = PolicyError.Unavailable(RuntimeException(why))

  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, value) => value }
    case _                => None

  private def text(json: Json, name: String): Option[String] = field(json, name).collect { case Json.Str(s) => s }

  private def call(request: Request): IO[PolicyError, (Status, String)] =
    ZIO
      .scoped(client.batched(request).flatMap(r => r.body.asString.map(r.status -> _)))
      .mapError(PolicyError.Unavailable(_))

  private def url(path: String) =
    ZIO.fromEither(URL.decode(config.url.stripSuffix("/") + path)).mapError(PolicyError.Unavailable(_))

  private val form =
    if config.clientSecret.isEmpty then config.tokenForm
    else s"${config.tokenForm}&client_secret=${java.net.URLEncoder.encode(config.clientSecret, "UTF-8")}"

  // A token each time: the check asks a few times a day.
  private def token: IO[PolicyError, String] =
    for
      at               <- ZIO.fromEither(URL.decode(config.tokenUrl)).mapError(PolicyError.Unavailable(_))
      (status, answer) <- call(
                            Request
                              .post(at, Body.fromString(form))
                              .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
                          )
      json  <- ZIO.fromEither(answer.fromJson[Json]).mapError(failed)
      token <- ZIO
                 .fromOption(text(json, "access_token").filter(_ => status.isSuccess))
                 .orElseFail(failed(s"no token for Approvals: $status ${answer.take(200)}"))
    yield token

  private def send(path: String, body: Json): IO[PolicyError, (Status, String)] =
    for
      bearer <- token
      at     <- url(path)
      answer <- call(
                  Request
                    .post(at, Body.fromString(body.toJson))
                    .addHeader(Header.Authorization.Bearer(bearer))
                    .addHeader(Header.ContentType(MediaType.application.json))
                )
    yield answer

  def ask(proposal: Proposal): IO[PolicyError, (String, String)] =
    val facts = Json.Obj(Chunk.fromIterable(proposal.facts.map((k, v) => k -> Json.Str(v))))
    val body  = Json.Obj(
      Chunk(
        "kind"        -> Json.Str(Proposal.kind),
        "subject"     -> Json.Str(proposal.subject),
        "title"       -> Json.Str(proposal.title),
        "before"      -> Json.Str(proposal.before),
        "after"       -> Json.Str(proposal.after),
        "facts"       -> facts,
        "impact"      -> proposal.impact.fold(Json.Null)(Json.Str(_)),
        "link"        -> proposal.link.fold(Json.Null)(Json.Str(_)),
        "requestedBy" -> Json.Obj("subject" -> Json.Str(proposal.requester), "name" -> Json.Str(proposal.requester))
      )
    )
    send("/requests", body).flatMap { (status, answer) =>
      val view = answer.fromJson[Json].toOption
      (view.flatMap(text(_, "id")), view.flatMap(text(_, "hash"))) match
        case (Some(id), Some(hash)) if status.isSuccess => ZIO.succeed(id -> hash)
        case _                                          => ZIO.fail(failed(s"Approvals did not take the request: $status ${answer.take(300)}"))
    }

  private def told(path: String, body: Json): IO[PolicyError, Unit] =
    send(path, body).flatMap { (status, answer) =>
      ZIO.unless(status.isSuccess)(ZIO.fail(failed(s"Approvals answered $status: ${answer.take(300)}"))).unit
    }

  def applied(request: String, hash: String): IO[PolicyError, Unit] =
    told(s"/requests/$request/applied", Json.Obj("hash" -> Json.Str(hash)))

  def applyFailed(request: String, why: String): IO[PolicyError, Unit] =
    told(s"/requests/$request/apply-failed", Json.Obj("reason" -> Json.Str(why)))
