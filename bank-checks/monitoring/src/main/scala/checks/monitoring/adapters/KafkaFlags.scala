package checks.monitoring.adapters

import checks.monitoring.domain.*
import checks.monitoring.service.FlagSink
import checks.platform.{Apicurio, Framing, KafkaSignIn}
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.producer.ProducerRecord
import zio.*
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.schema.codec.ProtobufCodec

import scala.jdk.CollectionConverters.*

final case class KafkaConfig(
  bootstrap: String,
  registry: String,
  replication: Short,
  signIn: KafkaSignIn = KafkaSignIn.anonymous
)

object KafkaFlags:
  val topic      = "checks.flags"
  val artifact   = "checks.flags.v1.Flag"
  val partitions = 12

  private val codec = ProtobufCodec.protobufCodec[Flag]

  def schema: Task[String] =
    ZIO.attemptBlocking(
      scala.io.Source.fromResource("checks/flags/v1/flag.proto").mkString
    )

  // As the bank's topics: made at start with a partition count that never changes, keyed by account.
  private def createTopic(config: KafkaConfig): Task[Unit] =
    ZIO.scoped {
      ZIO
        .fromAutoCloseable(
          ZIO.attempt(
            AdminClient.create(
              (Map[String, AnyRef](
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> config.bootstrap
              ) ++ config.signIn.properties).asJava
            )
          )
        )
        .flatMap { admin =>
          ZIO.attemptBlocking {
            val existing = admin.listTopics().names().get().asScala
            if !existing.contains(topic) then
              val _ = admin.createTopics(List(NewTopic(topic, partitions, config.replication)).asJava).all().get()
          }.unit
        }
    }

  def encode(flag: Flag, contentId: Int): Array[Byte] =
    Framing.frame(contentId, codec.encode(flag).toArray)

  val layer: RLayer[KafkaConfig, FlagSink] =
    ZLayer.scoped {
      for
        config     <- ZIO.service[KafkaConfig]
        registered <- schema.flatMap(Apicurio(config.registry).register(artifact, _))
        _          <- createTopic(config)
        producer   <- Producer.make(
                      ProducerSettings(List(config.bootstrap))
                        .withProperty("acks", "all")
                        .withProperties(config.signIn.properties)
                    )
      yield KafkaFlagsLive(producer, registered.contentId)
    }

final private case class KafkaFlagsLive(producer: Producer, contentId: Int) extends FlagSink:
  def publish(flags: List[Flag]): IO[MonitoringError, Unit] =
    ZIO
      .foreachDiscard(flags) { flag =>
        producer.produce(
          ProducerRecord(KafkaFlags.topic, flag.account, KafkaFlags.encode(flag, contentId)),
          Serde.string,
          Serde.byteArray
        )
      }
      .mapError(MonitoringError.Unpublished(_))
