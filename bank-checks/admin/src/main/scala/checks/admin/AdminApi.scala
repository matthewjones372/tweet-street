package checks.admin

import checks.access.domain.AccessError
import checks.access.service.Access
import checks.monitoring.service.MonitoringQueries
import checks.policy.domain.{Status as VersionStatus, *}
import checks.policy.service.{Policy, PolicyConfig}
import checks.screening.domain.Asked
import checks.screening.service.ScreeningQueries
import verdict.{Analysis, Evaluator, FieldType, Rule, RuleJson}
import zio.*
import zio.http.{
  handler,
  Body,
  Cookie,
  Header,
  MediaType,
  Method,
  Path,
  Request,
  Response,
  RoutePattern,
  Routes,
  Status,
  URL
}
import zio.http.codec.{HeaderCodec, HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

object AdminApi:
  val cookieName = "checks_session"
  val signingIn  = "checks_sign_in"

  val schema = Endpoint(RoutePattern.GET / "schema" / PathCodec.string("record"))
    .out[RecordBody]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val rules = Endpoint(RoutePattern.GET / "rules")
    .out[List[RuleSummary]]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val rule = Endpoint(RoutePattern.GET / "rules" / PathCodec.string("name"))
    .out[RuleBody]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val validate = Endpoint(RoutePattern.POST / "rules" / "validate")
    .in[DraftBody]
    .out[Checked]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val tryIt = Endpoint(RoutePattern.POST / "rules" / "try")
    .in[TryBody]
    .out[Tried]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val dryRun = Endpoint(RoutePattern.POST / "rules" / "dry-run")
    .in[DryRunBody]
    .out[DryRunResult]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val newVersion = Endpoint(RoutePattern.POST / "rules" / PathCodec.string("name") / "versions")
    .in[NewVersion]
    .out[VersionBody]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val decisions = Endpoint(RoutePattern.GET / "decisions")
    .query(HttpCodec.query[Int]("limit").optional)
    .out[List[DecisionView]]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val flags = Endpoint(RoutePattern.GET / "flags")
    .query(HttpCodec.query[Int]("limit").optional)
    .out[List[FlagView]]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val whoAmI = Endpoint(RoutePattern.GET / "me")
    .out[Who]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val testTransfer = Endpoint(RoutePattern.POST / "screen" / "test")
    .in[ProposedTransfer]
    .out[Tested]
    .header(HeaderCodec.cookie.optional)
    .outErrors[Problem](
      HttpCodec.error[Problem.NotSignedIn](Status.Unauthorized),
      HttpCodec.error[Problem.Invalid](Status.UnprocessableEntity),
      HttpCodec.error[Problem.NotFound](Status.NotFound),
      HttpCodec.error[Problem.Unavailable](Status.ServiceUnavailable)
    )

  val signOut = Endpoint(RoutePattern.POST / "logout").header(HeaderCodec.cookie.optional).out[Unit]

  val endpoints =
    List(
      schema,
      rules,
      rule,
      validate,
      tryIt,
      dryRun,
      newVersion,
      decisions,
      flags,
      whoAmI,
      testTransfer,
      signOut
    )

  private def subject(record: String): IO[Problem, Subject] = record match
    case "transfer" => ZIO.succeed(Subject.Transfers)
    case "movement" => ZIO.succeed(Subject.Movements)
    case other      => ZIO.fail(Problem.NotFound(s"no record $other: a rule reads a transfer or a movement"))

  private def record(subject: Subject): String = subject match
    case Subject.Transfers => "transfer"
    case Subject.Movements => "movement"

  private def token(cookies: Option[Header.Cookie]): Option[String] =
    cookies.flatMap(_.value.find(_.name == cookieName)).map(_.content)

  private def signedIn(cookies: Option[Header.Cookie]): ZIO[Access, Problem, String] =
    ZIO
      .fromOption(token(cookies))
      .orElseFail(Problem.NotSignedIn("sign in first"))
      .flatMap(found => ZIO.serviceWithZIO[Access](_.who(found)))
      .mapBoth(
        {
          case AccessError.Unavailable(cause) => Problem.Unavailable(cause.getMessage)
          case problem: Problem               => problem
          case other: AccessError             => Problem.NotSignedIn(other.getMessage)
        },
        _.name
      )

  private def unavailable(error: Throwable) = Problem.Unavailable(error.getMessage)

  private def parse(draft: DraftBody): IO[Problem, (Subject, Rule)] =
    for
      about     <- subject(draft.record)
      condition <- ZIO
                     .fromEither(RuleJson.load(draft.document, about.schema))
                     .mapError(errors => Problem.Invalid(errors.map(_.message)))
    yield (about, condition)

  private def comparisons(tpe: FieldType): List[String] = tpe match
    case FieldType.Number    => List("gt", "gte", "lt", "lte")
    case FieldType.Text      => List("eq", "in")
    case FieldType.Bool      => List("isTrue")
    case FieldType.Nested(_) => Nil

  private def view(version: Version) =
    val (approval, request, note) = version.approval match
      case Approval.NotNeeded       => ("not-needed", None, None)
      case Approval.Waiting(r, _)   => ("waiting", Some(r), None)
      case Approval.Given(r, _)     => ("given", Some(r), None)
      case Approval.Refused(r, how) => ("refused", Some(r), Some(how))
    VersionBody(
      version.number,
      version.status.toString.toLowerCase,
      version.severity.toString.toLowerCase,
      version.position,
      version.author,
      version.atMillis,
      Analysis.describe(version.condition),
      RuleJson.render(version.condition),
      approval,
      request,
      note
    )

  // How far the live version and the new one reach over the last week: the impact an approver sees beside the diff.
  private def reach(about: Subject, name: String, condition: Rule): ZIO[Needs, Problem, Reach] =
    val days                        = 7
    def dry(rule: Rule, from: Long) = about match
      case Subject.Transfers => ZIO.serviceWithZIO[ScreeningQueries](_.dryRun(rule, from)).mapError(unavailable)
      case Subject.Movements => ZIO.serviceWithZIO[MonitoringQueries](_.dryRun(rule, from)).mapError(unavailable)
    for
      from   <- since(days)
      live   <- ZIO.serviceWithZIO[Policy](_.live(about)).map(_.rules.find(_.name == name))
      before <- ZIO.foreach(live)(rule => dry(rule.condition, from))
      after  <- dry(condition, from)
    yield Reach(before.fold(0L)(_.matched), after.matched, after.of, days)

  private def summaries(versions: List[Version]): List[RuleSummary] =
    versions
      .groupBy(_.rule)
      .toList
      .map { (name, all) =>
        val latest = all.maxBy(_.number)
        val live   =
          all.filter(_.status != VersionStatus.Draft).maxByOption(_.number).filter(_.status == VersionStatus.Live)
        RuleSummary(
          name,
          record(latest.subject),
          live.map(_.number),
          latest.number,
          latest.status.toString.toLowerCase,
          latest.severity.toString.toLowerCase,
          latest.position
        )
      }
      .sortBy(summary => (summary.record, summary.position, summary.name))

  private def since(days: Int) =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map(_ - days.toLong * 24 * 60 * 60 * 1000)

  private def lowerEnum[E](values: Array[E], text: String, what: String): IO[Problem, E] =
    ZIO
      .fromOption(values.find(_.toString.equalsIgnoreCase(text)))
      .orElseFail(Problem.Invalid(List(s"$text is not a $what: one of ${values.mkString(", ").toLowerCase}")))

  type Needs = Access & Policy & PolicyConfig & ScreeningQueries & MonitoringQueries

  val routes: Routes[Needs, Nothing] = Routes(
    schema.implement { (name, cookies) =>
      signedIn(cookies) *> subject(name).map { about =>
        RecordBody(name, about.schema.fields.map(f => FieldBody(f.name, f.tpe.render, comparisons(f.tpe))))
      }
    },
    rules.implement { cookies =>
      signedIn(cookies) *> ZIO.serviceWithZIO[Policy](_.all).mapBoth(unavailable, summaries)
    },
    rule.implement { (name, cookies) =>
      signedIn(cookies) *> ZIO
        .serviceWithZIO[Policy](_.versions(name))
        .mapError {
          case PolicyError.NoSuchRule(missing) => Problem.NotFound(s"no rule $missing")
          case other                           => unavailable(other)
        }
        .map(versions => RuleBody(name, record(versions.head.subject), versions.map(view)))
    },
    validate.implement { (draft, cookies) =>
      signedIn(cookies) *> parse(draft).fold(
        {
          case Problem.Invalid(errors) => Checked(errors, None, None)
          case other                   => Checked(List(other.toString), None, None)
        },
        (_, condition) =>
          val simplest = Analysis.simplify(condition)
          Checked(
            Nil,
            Some(Analysis.describe(condition)),
            Option.when(simplest != condition)(Analysis.describe(simplest))
          )
      )
    },
    tryIt.implement { (body, cookies) =>
      signedIn(cookies) *> parse(DraftBody(body.record, body.document)).flatMap { (about, condition) =>
        val evaluated = about match
          case Subject.Transfers =>
            ZIO
              .fromOption(body.transfer)
              .orElseFail(Problem.Invalid(List("a rule over transfers is tried on a transfer")))
              .map(Evaluator.evaluate(condition, _, ProposedTransfer.rules))
          case Subject.Movements =>
            ZIO
              .fromOption(body.movement)
              .orElseFail(Problem.Invalid(List("a rule over movements is tried on a movement")))
              .map(Evaluator.evaluate(condition, _, Movement.rules))
        evaluated.flatMap(result =>
          ZIO.fromEither(result).mapBoth(error => Problem.Invalid(List(error.message)), e => Tried(e.held, e.render()))
        )
      }
    },
    dryRun.implement { (body, cookies) =>
      signedIn(cookies) *> parse(DraftBody(body.record, body.document)).flatMap { (about, condition) =>
        since(body.days.getOrElse(7)).flatMap { from =>
          val run = about match
            case Subject.Transfers => ZIO.serviceWithZIO[ScreeningQueries](_.dryRun(condition, from))
            case Subject.Movements => ZIO.serviceWithZIO[MonitoringQueries](_.dryRun(condition, from))
          run.mapBoth(
            unavailable,
            dry =>
              DryRunResult(dry.matched, dry.of, if dry.of == 0 then 0 else dry.matched.toDouble / dry.of, dry.examples)
          )
        }
      }
    },
    newVersion.implement { (name, body, cookies) =>
      for
        author   <- signedIn(cookies)
        about    <- subject(body.record)
        severity <- lowerEnum(Severity.values, body.severity, "severity")
        status   <- lowerEnum(VersionStatus.values, body.status, "status")
        draft     = Draft(name, about, body.document, severity, body.position, status)
        reached  <- ZIO.when(status == VersionStatus.Live)(parse(DraftBody(body.record, body.document)).flatMap {
                     (_, condition) => reach(about, name, condition)
                   })
        stored <- ZIO
                    .serviceWithZIO[Policy](_.propose(draft, author, reached))
                    .mapError {
                      case PolicyError.Unavailable(cause) => unavailable(cause)
                      case refused                        => Problem.Invalid(List(refused.getMessage))
                    }
      yield view(stored)
    },
    decisions.implement { (limit, cookies) =>
      signedIn(cookies) *> ZIO
        .serviceWithZIO[ScreeningQueries](_.recent(limit.getOrElse(50)))
        .mapBoth(
          unavailable,
          _.map(d => DecisionView(d.transfer, d.outcome.toString.toLowerCase, d.rule, d.version, d.evidence))
        )
    },
    flags.implement { (limit, cookies) =>
      signedIn(cookies) *> ZIO
        .serviceWithZIO[MonitoringQueries](_.recentFlags(limit.getOrElse(50)))
        .mapBoth(
          unavailable,
          _.map(f =>
            FlagView(f.account, f.sequence, f.rule, f.version, f.severity, f.atMillis, f.currency, f.amount, f.evidence)
          )
        )
    },
    whoAmI.implement(cookies =>
      signedIn(cookies).flatMap(name => ZIO.serviceWith[PolicyConfig](config => Who(name, config.approvalsPages)))
    ),
    testTransfer.implement { (proposed, cookies) =>
      signedIn(cookies) *> ZIO.serviceWithZIO[ScreeningQueries](_.preview(Asked("test", proposed, 0))).map { preview =>
        val d = preview.decision
        Tested(
          d.outcome.toString.toLowerCase,
          d.rule,
          d.version,
          preview.rules.map(w => RuleWeighed(w.rule, w.version, w.held, w.evidence))
        )
      }
    },
    signOut.implement { cookies =>
      ZIO.foreachDiscard(token(cookies))(found => ZIO.serviceWithZIO[Access](_.signOut(found)).ignore)
    }
  )

  // Signing in is the identity provider's (lark-bank spec 0019): sent there with a state this browser keeps, and
  // back to /callback, which opens a session if the provider says the person is in one of the admins' groups.
  // Behind the ingress's HTTPS the cookies are Secure; a test talks plain HTTP to the port, and they cannot be.
  private def secure(request: Request) = request.headers.get("X-Forwarded-Proto").contains("https")

  private def cookie(name: String, value: String, request: Request, maxAge: Option[Duration], strict: Boolean) =
    Header.SetCookie(
      Cookie.Response(
        name,
        value,
        path = Some(Path.root),
        isHttpOnly = true,
        isSecure = secure(request),
        maxAge = maxAge,
        // The provider's redirect back is a cross-site navigation: only a Lax cookie rides it.
        sameSite = Some(if strict then Cookie.SameSite.Strict else Cookie.SameSite.Lax)
      )
    )

  private def page(status: Status, text: String) =
    Response(status = status, body = Body.fromString(text)).contentType(MediaType.text.plain)

  private def refused(error: AccessError) = error match
    case AccessError.NotAnAdmin(_)   => page(Status.Forbidden, error.getMessage)
    case AccessError.ProviderDown(_) => page(Status.ServiceUnavailable, error.getMessage)
    case AccessError.Unavailable(_)  => page(Status.ServiceUnavailable, error.getMessage)
    case _                           => page(Status.BadRequest, error.getMessage)

  val signInRoutes: Routes[Access, Nothing] = Routes(
    Method.GET / "login" -> handler { (request: Request) =>
      ZIO
        .serviceWithZIO[Access](_.begin(request.queryParam("return").getOrElse("/")))
        .fold(
          refused,
          (url, pending) =>
            Response(Status.Found)
              .addHeader(Header.Location(URL.decode(url).toOption.get))
              .addHeader(cookie(signingIn, pending.state, request, Some(10.minutes), strict = false))
        )
    },
    Method.GET / "callback" -> handler { (request: Request) =>
      val sameBrowser = request.cookie(signingIn).map(_.content)
      (request.queryParam("error"), request.queryParam("state"), request.queryParam("code")) match
        case (Some(error), _, _)          => ZIO.succeed(page(Status.BadRequest, s"the identity provider said: $error"))
        case (_, Some(state), Some(code)) =>
          ZIO
            .serviceWithZIO[Access](_.finish(state, code, sameBrowser))
            .fold(
              refused,
              (session, returnTo) =>
                Response(Status.Found)
                  .addHeader(Header.Location(URL.decode(returnTo).toOption.getOrElse(URL.root)))
                  .addHeader(cookie(cookieName, session.token, request, None, strict = true))
                  .addHeader(cookie(signingIn, "", request, Some(Duration.Zero), strict = false))
            )
        case _ => ZIO.succeed(page(Status.BadRequest, "a callback names its state and code"))
    }
  )
