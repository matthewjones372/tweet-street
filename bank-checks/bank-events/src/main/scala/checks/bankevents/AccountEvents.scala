package checks.bankevents

import bank.events.v1.AccountEvent
import checks.monitoring.domain.Seen
import checks.monitoring.service.Monitoring
import checks.platform.KafkaSignIn
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
import io.opentelemetry.api.OpenTelemetry
import zio.*
import zio.metrics.Metric
import zio.kafka.consumer.{Consumer, ConsumerSettings, OffsetBatch, Subscription}
import zio.stream.ZStream
import zio.kafka.serde.{Deserializer, Serde}

final case class AccountEventsConfig(
  bootstrap: String,
  registry: String,
  group: String,
  signIn: KafkaSignIn = KafkaSignIn.anonymous
)

object AccountEvents:
  val topic = "bank.account-events"

  def seen(event: AccountEvent): Seen =
    Seen(event.getAccountId, event.getSequence, event.getAtMillis, Movements.from(event))

  // As lark-bank-events' README tells a consumer to read the bank's records.
  private def values(config: AccountEventsConfig): Task[Deserializer[Any, AccountEvent]] =
    val properties = Map[String, AnyRef](
      "apicurio.registry.url"                             -> config.registry,
      "apicurio.registry.serde.read-indexes"              -> java.lang.Boolean.TRUE,
      "apicurio.registry.serde.read-type-ref"             -> java.lang.Boolean.FALSE,
      "apicurio.registry.deserializer.value.return-class" -> classOf[AccountEvent].getName
    )
    Deserializer.fromKafkaDeserializer(ProtobufKafkaDeserializer[AccountEvent](), properties, false)

  def settings(config: AccountEventsConfig): ConsumerSettings =
    ConsumerSettings(List(config.bootstrap))
      .withGroupId(config.group)
      .withProperty("auto.offset.reset", "earliest")
      .withProperties(config.signIn.properties)

  // At least once: each partition in order, an offset committed only once its event is kept and its flags acked.
  val run: ZIO[AccountEventsConfig & Consumer & Monitoring, Throwable, Unit] = runWith(OpenTelemetry.noop())

  // As run, each event published in a trace checked in it (lark-bank spec 0024).
  def runWith(telemetry: OpenTelemetry): ZIO[AccountEventsConfig & Consumer & Monitoring, Throwable, Unit] =
    for
      config  <- ZIO.service[AccountEventsConfig]
      decoder <- values(config)
      _       <- ZStream
             .serviceWithStream[Consumer](_.partitionedStream(Subscription.topics(topic), Serde.string, decoder))
             .flatMapPar(Int.MaxValue) { case (_, partition) =>
               partition.mapZIO(record =>
                 Handled
                   .inTrace(telemetry, "bank-checks monitoring", record.record)(Monitoring.check(seen(record.value)))
                   .as(record.offset)
               )
             }
             .groupedWithin(1000, 1.second)
             .mapZIO(offsets => OffsetBatch(offsets).commit)
             .runDrain
    yield ()

  // The consumer's own records-lag-max, as a gauge: the alert watches it grow.
  val lag: ZIO[Consumer, Nothing, Unit] =
    ZIO
      .serviceWithZIO[Consumer](_.metrics)
      .map(_.collectFirst {
        case (name, metric) if name.name == "records-lag-max" && name.tags.isEmpty =>
          metric.metricValue match
            case value: java.lang.Double if !value.isNaN => value.doubleValue
            case _                                       => 0.0
      }.getOrElse(0.0))
      .flatMap(Metric.gauge("checks_monitoring_consumer_lag").set)
      .ignore
      .repeat(Schedule.spaced(15.seconds))
      .unit

  def consumer(config: AccountEventsConfig): RLayer[Any, Consumer] =
    ZLayer.scoped(Consumer.make(settings(config)))
