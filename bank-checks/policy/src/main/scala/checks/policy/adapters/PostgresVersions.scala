package checks.policy.adapters

import checks.platform.ZTransactor
import checks.policy.domain.*
import checks.policy.service.Versions
import com.augustnagro.magnum.*
import org.postgresql.PGConnection
import verdict.{Rule, RuleJson}
import zio.*
import zio.stream.*

import javax.sql.DataSource

final private case class VersionRow(
  rule: String,
  number: Int,
  subject: String,
  document: String,
  severity: String,
  position: Int,
  status: String,
  author: String,
  atMillis: Long,
  approval: String,
  approvalRequest: Option[String],
  contentHash: Option[String],
  approvalNote: Option[String]
) derives DbCodec

private object ApprovalColumns:
  // An approval as its four columns, and back.
  def of(approval: Approval): (String, Option[String], Option[String], Option[String]) = approval match
    case Approval.NotNeeded              => ("NotNeeded", None, None, None)
    case Approval.Waiting(request, hash) => ("Waiting", Some(request), Some(hash), None)
    case Approval.Given(request, hash)   => ("Given", Some(request), Some(hash), None)
    case Approval.Refused(request, how)  => ("Refused", Some(request), None, Some(how))

  def from(row: VersionRow): Approval = (row.approval, row.approvalRequest) match
    case ("Waiting", Some(request)) => Approval.Waiting(request, row.contentHash.getOrElse(""))
    case ("Given", Some(request))   => Approval.Given(request, row.contentHash.getOrElse(""))
    case ("Refused", Some(request)) => Approval.Refused(request, row.approvalNote.getOrElse(""))
    case _                          => Approval.NotNeeded

object PostgresVersions:
  val layer: RLayer[DataSource, Versions] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresVersionsLive(ZTransactor(dataSource), dataSource))

final private case class PostgresVersionsLive(transactor: ZTransactor, dataSource: DataSource) extends Versions:
  def insert(
    draft: Draft,
    condition: Rule,
    author: String,
    atMillis: Long,
    approval: Approval
  ): IO[PolicyError, Version] =
    transactor.transact {
      val earlier = sql"SELECT subject FROM policy.rule_version WHERE rule = ${draft.rule} LIMIT 1"
        .query[String]
        .run()
        .headOption
        .map(Subject.valueOf)
      earlier.filter(_ != draft.subject) match
        case Some(was) => Left(PolicyError.SubjectChanged(draft.rule, was, draft.subject))
        case None      =>
          val number = sql"SELECT COALESCE(MAX(number), 0) + 1 FROM policy.rule_version WHERE rule = ${draft.rule}"
            .query[Int]
            .run()
            .head
          val (state, request, hash, note) = ApprovalColumns.of(approval)
          val row                          = VersionRow(
            draft.rule,
            number,
            draft.subject.toString,
            RuleJson.render(condition),
            draft.severity.toString,
            draft.position,
            draft.status.toString,
            author,
            atMillis,
            state,
            request,
            hash,
            note
          )
          val _ = sql"""INSERT INTO policy.rule_version
                  (rule, number, subject, document, severity, position, status, author, at_millis,
                   approval, approval_request, content_hash, approval_note)
                  VALUES (${row.rule}, ${row.number}, ${row.subject}, ${row.document}, ${row.severity},
                          ${row.position}, ${row.status}, ${row.author}, ${row.atMillis},
                          ${row.approval}, ${row.approvalRequest}, ${row.contentHash}, ${row.approvalNote})""".update
            .run()
          Right(toVersion(row, condition))
    }
      .mapError(PolicyError.Unavailable(_))
      .absolve

  def all: IO[PolicyError, List[Version]] =
    transactor.connect {
      sql"""SELECT rule, number, subject, document, severity, position, status, author, at_millis,
                   approval, approval_request, content_hash, approval_note
              FROM policy.rule_version ORDER BY rule, number""".query[VersionRow].run().toList
    }
      .mapError(PolicyError.Unavailable(_))
      .flatMap(rows => ZIO.foreach(rows)(read))

  def asked(request: String): IO[PolicyError, Option[Version]] =
    transactor.connect {
      sql"""SELECT rule, number, subject, document, severity, position, status, author, at_millis,
                   approval, approval_request, content_hash, approval_note
              FROM policy.rule_version WHERE approval_request = $request""".query[VersionRow].run().headOption
    }
      .mapError(PolicyError.Unavailable(_))
      .flatMap(row => ZIO.foreach(row)(read))

  // Only a version still waiting is decided: an answer heard twice changes nothing the second time.
  def decide(rule: String, number: Int, approval: Approval): IO[PolicyError, Unit] =
    val (state, request, hash, note) = ApprovalColumns.of(approval)
    transactor.connect {
      val _ = sql"""UPDATE policy.rule_version
                      SET approval = $state, approval_request = $request, content_hash = COALESCE($hash, content_hash),
                          approval_note = $note
                    WHERE rule = $rule AND number = $number AND approval = 'Waiting'""".update.run()
    }.mapError(PolicyError.Unavailable(_))

  // A stored version was validated before it was stored, so one that does not parse is a defect, not a refusal.
  private def read(row: VersionRow): IO[PolicyError, Version] =
    ZIO
      .fromEither(RuleJson.parse(row.document))
      .mapBoth(reason => PolicyError.Invalid(List(s"${row.rule} version ${row.number}: $reason")), toVersion(row, _))

  private def toVersion(row: VersionRow, condition: Rule) =
    Version(
      row.rule,
      row.number,
      Subject.valueOf(row.subject),
      condition,
      Severity.valueOf(row.severity),
      row.position,
      Status.valueOf(row.status),
      row.author,
      row.atMillis,
      ApprovalColumns.from(row)
    )

  // One connection held for LISTEN, read on a blocking thread; a lost one is opened again after a second.
  def changes: ZStream[Any, Nothing, Unit] =
    ZStream
      .scoped(ZIO.fromAutoCloseable(ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        connection.createStatement().execute("LISTEN policy_changed")
        connection
      }))
      .flatMap { connection =>
        val listening = connection.unwrap(classOf[PGConnection])
        ZStream.repeatZIO(ZIO.attemptBlocking(Option(listening.getNotifications(1000)).map(_.length).getOrElse(0)))
      }
      .collect { case notified if notified > 0 => () }
      .catchAll(error => ZStream.fromZIO(ZIO.logWarning(s"policy_changed not heard: $error")).drain)
      .repeat(Schedule.spaced(1.second))
