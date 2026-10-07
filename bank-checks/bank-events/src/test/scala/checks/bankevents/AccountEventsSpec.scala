package checks.bankevents

import bank.events.v1.*
import checks.monitoring.adapters.{KafkaConfig, KafkaFlags, PostgresMovements}
import checks.monitoring.service.*
import checks.platform.{Apicurio, Framing, TestPostgres, ZTransactor}
import checks.policy.domain.*
import checks.policy.service.LiveRulesSource
import com.augustnagro.magnum.*
import org.apache.kafka.clients.producer.ProducerRecord
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import verdict.{FieldPath, Rule}
import zio.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.test.*

import javax.sql.DataSource

final class Registry extends GenericContainer[Registry]("quay.io/apicurio/apicurio-registry:3.3.3")

object AccountEventsSpec extends ZIOSpecDefault:
  private val kafka = ZLayer.scoped(ZIO.acquireRelease(ZIO.attemptBlocking {
    val started = KafkaContainer("apache/kafka:3.9.1")
    started.start()
    started
  })(container => ZIO.attemptBlocking(container.stop()).orDie))

  private val apicurio = ZLayer.scoped(ZIO.acquireRelease(ZIO.attemptBlocking {
    val started = Registry()
      .withExposedPorts(Integer.valueOf(8080))
      .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forStatusCode(200))
    started.start()
    started
  })(container => ZIO.attemptBlocking(container.stop()).orDie))

  private def resource(path: String) =
    ZIO.attemptBlocking(scala.io.Source.fromResource(path).mkString)

  // The bank's own registration, as lark-bank does it at start: money first, account events referring to it.
  private def registerAsTheBank(registry: Apicurio) =
    for
      money   <- resource("bank/events/v1/money.proto").flatMap(registry.register("bank.events.v1.money", _))
      account <- resource("bank/events/v1/account.proto")
                   .flatMap(
                     registry.register(
                       "bank.events.v1.AccountEvent",
                       _,
                       List(registry.reference(money, "bank/events/v1/money.proto"))
                     )
                   )
    yield account.contentId

  private val large = LiveRules(
    Subject.Movements,
    List(
      LiveRule(
        "large-withdrawal",
        1,
        Severity.High,
        0,
        Rule.And(Rule.EqStr(FieldPath.unsafe("kind"), "withdrawal"), Rule.Gte(FieldPath.unsafe("amount"), 100))
      )
    )
  )

  private def event(sequence: Int): AccountEvent =
    val amount = Money.newBuilder.setCurrency("GBP").setAmount(f"${sequence * 10}%d.00").build
    val base   =
      AccountEvent.newBuilder.setAccountId(s"acc-${sequence % 3}").setSequence(sequence).setAtMillis(1_700_000_000_000L)
    if sequence % 2 == 0 then
      base.setWithdrawn(Withdrawn.newBuilder.setAmount(amount).setReference(s"w-$sequence")).build
    else base.setCredited(Credited.newBuilder.setAmount(amount).setTransferId(s"t-$sequence")).build

  private def movements(dataSource: DataSource) =
    ZTransactor(dataSource).connect(sql"SELECT count(*) FROM monitoring.movement".query[Long].run().head)

  private def flagged(dataSource: DataSource) =
    ZTransactor(dataSource).connect(sql"SELECT count(*) FROM monitoring.flag".query[Long].run().head)

  def spec = suite("Account events")(
    test("a consumer stopped mid-run and started again leaves no event unchecked") {
      for
        kafkaC     <- ZIO.service[KafkaContainer]
        apicurioC  <- ZIO.service[Registry]
        dataSource <- ZIO.service[DataSource]
        registry    = s"http://${apicurioC.getHost}:${apicurioC.getMappedPort(8080)}/apis/registry/v3"
        bootstrap   = kafkaC.getBootstrapServers
        contentId  <- registerAsTheBank(Apicurio(registry))
        events      = (1 to 400).map(event)
        _          <- ZIO.scoped(Producer.make(ProducerSettings(List(bootstrap))).flatMap { producer =>
               ZIO.foreachDiscard(events)(e =>
                 producer.produce(
                   ProducerRecord(AccountEvents.topic, e.getAccountId, Framing.frame(contentId, e.toByteArray)),
                   Serde.string,
                   Serde.byteArray
                 )
               )
             })
        config     = AccountEventsConfig(bootstrap, registry, "checks-monitoring")
        monitoring = ZLayer.succeed(LiveRulesSource.fixed(large)) ++
                       (ZLayer.succeed(dataSource) >>> PostgresMovements.layer) ++
                       (ZLayer.succeed(KafkaConfig(bootstrap, registry, 1)) >>> KafkaFlags.layer) >>> Monitoring.layer
        running = AccountEvents.run.provide(ZLayer.succeed(config), AccountEvents.consumer(config), monitoring)
        // Stopped as soon as some are checked, well before all are.
        first     <- running.fork
        _         <- movements(dataSource).repeatUntil(_ >= 50).timeout(60.seconds)
        _         <- first.interrupt
        partway   <- movements(dataSource)
        second    <- running.fork
        started   <- Clock.nanoTime
        all       <- movements(dataSource).repeatUntil(_ >= 400).timeout(180.seconds)
        ended     <- Clock.nanoTime
        seen      <- movements(dataSource)
        _         <- ZIO.logInfo(s"checked $partway before the stop, $seen after, in ${(ended - started) / 1_000_000} ms")
        _         <- second.interrupt
        flags     <- flagged(dataSource)
        published <-
          ZIO.scoped(
            Consumer
              .make(
                ConsumerSettings(List(bootstrap)).withGroupId("reader").withProperty("auto.offset.reset", "earliest")
              )
              .flatMap(
                _.plainStream(Subscription.topics(KafkaFlags.topic), Serde.string, Serde.byteArray)
                  .take(flags)
                  .runCount
                  .timeout(60.seconds)
              )
          )
      // Withdrawals of 100.00 or more: the even sequences from 10 up, 196 of them.
      yield assertTrue(partway < 400L, all.contains(400L), flags == 196L, published.contains(flags))
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(8.minutes)
  ).provideShared(kafka, apicurio, TestPostgres.migrated("monitoring").orDie)
