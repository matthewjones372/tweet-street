package checks.bankevents

import bank.events.v1.ApprovalEvent
import bank.events.v1.ApprovalEvent.EventCase
import checks.policy.domain.Proposal
import checks.policy.service.{Decision, Policy}
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
import zio.*
import zio.kafka.consumer.{Consumer, OffsetBatch, Subscription}
import zio.kafka.serde.{Deserializer, Serde}
import zio.stream.ZStream

// What Approvals decided about the check's own requests (lark-bank spec 0019), as Policy's language: given, for content
// with a hash, or ended without it. Every other kind, and every other event, is someone else's or only the record.
object ApprovalEvents:
  val topic = "bank.approval-events"

  def decision(event: ApprovalEvent): Option[Decision] =
    if event.getKind != Proposal.kind then None
    else
      event.getEventCase match
        case EventCase.APPROVAL_GIVEN => Some(Decision.Given(event.getRequestId, event.getApprovalGiven.getContentHash))
        case EventCase.AUTO_APPROVED  => Some(Decision.Given(event.getRequestId, event.getAutoApproved.getContentHash))
        case EventCase.REJECTED       => Some(Decision.Ended(event.getRequestId, "rejected"))
        case EventCase.WITHDRAWN      => Some(Decision.Ended(event.getRequestId, "withdrawn"))
        case EventCase.SUPERSEDED     => Some(Decision.Ended(event.getRequestId, "superseded"))
        case EventCase.EXPIRED        => Some(Decision.Ended(event.getRequestId, "expired"))
        case _                        => None

  private def values(config: AccountEventsConfig): Task[Deserializer[Any, ApprovalEvent]] =
    val properties = Map[String, AnyRef](
      "apicurio.registry.url"                             -> config.registry,
      "apicurio.registry.serde.read-indexes"              -> java.lang.Boolean.TRUE,
      "apicurio.registry.serde.read-type-ref"             -> java.lang.Boolean.FALSE,
      "apicurio.registry.deserializer.value.return-class" -> classOf[ApprovalEvent].getName
    )
    Deserializer.fromKafkaDeserializer(ProtobufKafkaDeserializer[ApprovalEvent](), properties, false)

  // At least once, in each request's order: an offset is committed once Policy has taken its event.
  val run: ZIO[AccountEventsConfig & Consumer & Policy, Throwable, Unit] =
    for
      config  <- ZIO.service[AccountEventsConfig]
      decoder <- values(config)
      _       <- ZStream
             .serviceWithStream[Consumer](_.partitionedStream(Subscription.topics(topic), Serde.string, decoder))
             .flatMapPar(Int.MaxValue) { case (_, partition) =>
               partition.mapZIO(record =>
                 ZIO
                   .foreachDiscard(decision(record.value))(d => ZIO.serviceWithZIO[Policy](_.decided(d)))
                   .as(record.offset)
               )
             }
             .groupedWithin(100, 1.second)
             .mapZIO(offsets => OffsetBatch(offsets).commit)
             .runDrain
    yield ()
