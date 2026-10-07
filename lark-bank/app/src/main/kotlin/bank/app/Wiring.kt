package bank.app

import java.util.concurrent.ConcurrentHashMap
import bank.api.Signing
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.cluster.topic
import bank.protocol.wire.NodeSnapshot
import bank.protocol.NodeSnapshots
import arrow.core.Either
import bank.api.Busy
import bank.api.Healthy
import bank.api.PayrollAccepted
import bank.api.Payrolls
import bank.api.bankApi
import bank.api.Rules
import io.github.matthewjones372.pelican.Api
import bank.domain.AccountId
import bank.domain.Currencies
import bank.domain.Bank
import bank.domain.TransferId
import bank.protocol.AccountMessage
import bank.protocol.AccountMessages
import bank.protocol.AccountStates
import bank.domain.Money
import bank.protocol.BulkCredit
import bank.protocol.BulkCreditSent
import bank.protocol.Kinds
import bank.protocol.TransferMessage
import bank.protocol.TransferMessages
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Producer
import io.github.matthewjones372.lark.actor.Prune
import io.github.matthewjones372.lark.actor.every
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.ShardedSnapshots
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.journal.jdbc.GroupCommit
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcOffsets
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcSliceTable
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcSnapshots
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.snapshots
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.Health
import io.github.matthewjones372.lark.app.HealthRegistry
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.actor.Actors
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.actor.spawn
import io.github.matthewjones372.lark.app.cluster.cluster
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.liquibase.Migrated
import io.github.matthewjones372.lark.app.liquibase.migrations
import io.github.matthewjones372.lark.app.probe
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.spread
import io.github.matthewjones372.lark.actor.projection.Projection
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.sharding
import io.github.matthewjones372.lark.cluster.singleton
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.micrometer.core.instrument.Metrics as MicrometerRegistries
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.Clock
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.slf4j.bridge.SLF4JBridgeHandler
import javax.sql.DataSource
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.Scope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.ui.LoggerUIService
import kotlin.time.Duration.Companion.seconds
import io.github.matthewjones372.lark.stream.Actors as StreamActors

// ---- storage ----

/**
 * Liquibase's lines into the log, as JSON like every other (bank spec 0023): its own through java.util.logging, bridged
 * to SLF4J, and what it would print to stdout ("Running Changeset") through that log instead. Its scope is kept per
 * thread and handed to the threads started after, so this runs as this file's
 * modules are defined, before any node is built.
 */
@Suppress("unused")
private val liquibaseLogged: Unit = run {
    Scope.enter(mapOf(Scope.Attr.ui.name to LoggerUIService()))
    // The update summary has a switch of its own.
    System.setProperty("liquibase.command.showSummaryOutput", "LOG")
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
}

private fun pool(settings: DatabaseSettings, url: String = settings.url, name: String = "bank"): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = settings.user
            password = settings.password
            maximumPoolSize = settings.poolSize
            poolName = name
        },
    )

/** The journal's databases other than `bank.database`, each migrated to lark's tables before anything writes to it. */
class JournalDatabases(val others: List<Pair<String, HikariDataSource>>) {
    fun close() = others.forEach { (_, pool) -> pool.close() }
}

private fun journalDatabases(settings: DatabaseSettings, journal: JournalSettings): JournalDatabases =
    JournalDatabases(
        journal.others.map { (name, url) ->
            name to pool(settings, url, "journal-$name").also { pool -> migrate(pool, "db/journal.xml") }
        },
    )

private fun migrate(data: DataSource, changelog: String) = data.connection.use { connection ->
    val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { it.update(Contexts(), LabelExpression()) }
}

/** The journal every node shares, split by id across its databases, and the offsets the read models keep. */
class Persistence(val journal: ShardedJournal, val offsets: JdbcOffsets, val heads: JournalHeads)

val storage: Module =
    singleOf({ settings: DatabaseSettings -> pool(settings) }, { pool -> pool.close() }).boundTo<DataSource>() +
        migrations("db/changelog.xml") +
        single { settings: DatabaseSettings, journal: JournalSettings ->
            install({ journalDatabases(settings, journal) }) { databases, _ -> databases.close() }
        } +
        // A currency the accounts hold that the registry no longer lists, or lists with other places, would misread every
        // amount in it: the node refuses to start instead (bank spec 0011).
        single { data: DataSource, _: Migrated, currencies: Currencies -> JdbcReadModels(data).also {
            it.heldIn(currencies)
            it.canReadAsCaller()
        } } +
        single { data: DataSource, _: Migrated -> JdbcGrants(data) } +
        single { data: DataSource, _: Migrated -> JdbcAccessLog(data) }

// ---- telemetry ----

/**
 * Prometheus, with the JVM's own meters, and histograms for the latencies a dashboard takes a p99 of, each bucket
 * keeping the trace of a sampled request that fell in it (bank spec 0024).
 */
private fun prometheus(): PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry(), Clock.SYSTEM, CurrentSpan).also { registry ->
    registry.config().meterFilter(
        object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                if (id.name == "http.server.request.duration" || id.name.endsWith(".duration")) {
                    DistributionStatisticConfig.builder().percentilesHistogram(true).build().merge(config)
                } else {
                    config
                }
        },
    )
    listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics()).forEach { it.bindTo(registry) }
}

private val telemetry: Module =
    // Added to Micrometer's global composite, which is where lark-micrometer writes.
    singleOf<PrometheusMeterRegistry>(
        { prometheus().also(MicrometerRegistries::addRegistry) },
        { registry ->
            MicrometerRegistries.removeRegistry(registry)
            registry.close()
        },
    ) +
        single { settings: TelemetrySettings, node: NodeSettings ->
            install({ openTelemetry(settings, node) }) { sdk, _ -> sdk.close() }
        } +
        single { sdk: OpenTelemetrySdk -> sdk.getTracer("lark-bank") }.boundTo<Tracer>() +
        single { registry: PrometheusMeterRegistry, sdk: OpenTelemetrySdk -> Watched(registry, sdk) }

/** The meters and the spans, as one for the API, which takes both. */
class Watched(val registry: PrometheusMeterRegistry, val sdk: OpenTelemetrySdk)

/**
 * The SDK every span is made through (bank spec 0024): W3C's `traceparent` on the way in and out, sampled where the
 * request enters at [TelemetrySettings.sampled] and as its parent was after, batched to Tempo when enabled.
 */
private fun openTelemetry(settings: TelemetrySettings, node: NodeSettings): OpenTelemetrySdk {
    val resource = Resource.getDefault().merge(
        Resource.create(Attributes.of(SERVICE_NAME, "lark-bank", SERVICE_INSTANCE_ID, node.name)),
    )
    val tracing = SdkTracerProvider.builder()
        .setResource(resource)
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.sampled)))
        .apply {
            if (settings.enabled) {
                val exporter = OtlpHttpSpanExporter.builder().setEndpoint(settings.otlp.trimEnd('/') + "/v1/traces").build()
                addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            }
        }
        .build()
    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracing)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
}

private val SERVICE_NAME = AttributeKey.stringKey("service.name")
private val SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id")

// ---- the cluster and its entities ----

class Entities(val accounts: Sharded<AccountMessage>, val transfers: Sharded<TransferMessage>) {
    fun account(id: String): ActorRef<AccountMessage> = accounts.entity(id)

    fun transfer(id: String): ActorRef<TransferMessage> = transfers.entity(id)
}

/** Where the read models publish every event (spec 0015), when Kafka is on. */
class Publishing(val publisher: Publisher?)

private val clustered: Module =
    actors() +
        single { actors: Actors, data: DataSource, _: Migrated, settings: JournalSettings, others: JournalDatabases ->
            // Set on the flock before any entity starts: a persistent entity reads its journal from the flock.
            val databases = listOf(settings.primary to data) + others.others
            actors.within {
                // Which database owns which slices is a table in the primary (lark spec 0105), written on first start
                // from the databases configured then. One added later owns nothing until a range is moved to it.
                val slices = JdbcSliceTable(data, databases.map { it.first })
                val grouped = if (settings.groupCommit) GroupCommit() else null
                val journals = databases.map { (name, source) -> name to JdbcJournal(source, groupCommit = grouped) }
                val shared = ShardedJournal(journals, slices)
                journal(shared)
                snapshots(ShardedSnapshots(databases.map { (name, source) -> name to JdbcSnapshots(source) }, slices))
                Persistence(shared, JdbcOffsets(data), JournalHeads(databases))
            }
        } +
        // Joined as bank.cluster says, through whichever backend `join` names (lark spec 0096); ready once fully in.
        cluster("bank.cluster") +
        single { settings: ScreeningSettings, sdk: OpenTelemetrySdk -> screening(settings, sdk) } +
        single { actors: Actors, cluster: Cluster, settings: EntitySettings, persistence: Persistence, kafka: KafkaSettings,
            reading: ReadModelSettings, screening: Screening, tracer: Tracer ->
            // Old events go only once every read model that reads accounts has read them, in the id's own partition.
            val readers = listOfNotNull(ReadModelNames.STATEMENTS, ReadModelNames.PUBLISHED_ACCOUNTS.takeIf { kafka.enabled })
            val prune = Prune.after(persistence.offsets, persistence.journal, reading.partitions, *readers.toTypedArray())
            val snapshots = every(settings.snapshotEvery, AccountStates, prune)
            actors.within {
                val accounts = cluster.sharding(Kinds.ACCOUNT, AccountMessages, settings.passivateAfter, settings.shards) { id ->
                    account(AccountId(id), snapshots, settings.batch, tracer)
                }
                val transfers = cluster.sharding(Kinds.TRANSFER, TransferMessages, settings.passivateAfter, settings.shards) { id ->
                    transfer(TransferId(id), AccountsById(accounts::entity), settings.legTimeout, screening, tracer)
                }
                Entities(accounts, transfers)
            }
        } +
        single { actors: Actors, entities: Entities ->
            // Drained when the flock closes, before the node leaves: a credit sent is a credit confirmed.
            actors.within { entities.accounts.reliable("payroll") }
        } +
        single { actors: Actors -> actors.within { StreamActors(this) } }.boundTo<StreamBackend>()

// ---- the bank ----

/** A payroll's credits, each sent reliably. A resend after `Busy` is safe: each credit's reference is its own. */
class ReliablePayrolls(private val producer: Producer<AccountMessage>) : Payrolls {
    override fun send(id: String, credits: List<Pair<AccountId, Money>>): Either<Busy, PayrollAccepted> {
        credits.forEachIndexed { sent, (account, amount) ->
            val credited = BulkCredit(amount, "payroll:$id:$account")
            producer.send(account.value) { delivery -> BulkCreditSent(credited, delivery) }
                .onLeft { return Either.Left(Busy("$sent of ${credits.size} credits sent; send the payroll again")) }
        }
        return Either.Right(PayrollAccepted(id, credits.size))
    }
}

/** What the read models run on, and where they publish. */
class ReadSide(val backend: StreamBackend, val publishing: Publishing, val kafka: KafkaSettings, val settings: ReadModelSettings)

/** What `GET /cluster` answers, asked afresh each time. */
fun interface ClusterViews {
    fun current(): bank.api.ClusterView
}

private val theBank: Module =
    single { entities: Entities, settings: EntitySettings, tracer: Tracer ->
        TracedBank(ShardedBank(entities::account, entities::transfer, settings.askTimeout, settings.transferWait), tracer)
    }.boundTo<Bank>() +
        singleOf(::ReliablePayrolls).boundTo<Payrolls>() +
        single { kafka: KafkaSettings ->
            install({ Publishing(if (kafka.enabled) kafkaPublisher(kafka) else null) }) { publishing, _ -> publishing.publisher?.close() }
        } +
        singleOf(::ReadSide) +
        single { actors: Actors, cluster: Cluster, node: NodeSettings, persistence: Persistence, reads: JdbcReadModels,
            entities: Entities, side: ReadSide ->
            actors.within {
                // Each journal database's read models in partitions by slice, spread over the nodes (spec 0014): a
                // worker per database and partition, moved with its offsets when its node goes.
                val partitions = side.settings.partitions
                val parties = TransferParties(persistence.journal)
                cluster.spread("read-models", persistence.journal.feeds.size * partitions) { worker ->
                    Projection.worker {
                        val share = share(persistence.journal, partitions, worker)
                        logInfo("read models of ${share.database} partition ${share.partition} starting on ${node.name}")
                        startReadModels(
                            share, persistence.offsets, reads, side.settings, side.publishing.publisher, parties, side.backend,
                        )
                    }
                }
                // The sweeper reads the read models, not a feed, and runs once in the cluster.
                cluster.singleton("read-models", WhereAreReadModelsCodec) {
                    readModelsSingleton(node.name) {
                        logInfo("the sweeper starting on ${node.name}")
                        startSweeper(reads, entities::transfer, side.settings, side.backend)
                    }
                }
            }
        }

// ---- support's grants ----

/** The grants heard from Approvals on this node, while it runs; nothing when access is off. */
class GrantsHeard(val running: Running<Nothing, Long>?)

private val access: Module =
    single { settings: AccessSettings, kafka: KafkaSettings, grants: JdbcGrants, backend: StreamBackend ->
        install({
            GrantsHeard(
                if (!settings.enabled) null
                else grantsHeard(
                    kafka,
                    GrantKeeper(grants, ApprovalsOwner(settings.approvalsUrl, settings.tokenUrl, settings.tokenForm, settings.clientSecret)),
                    backend,
                ),
            )
        }) { heard, _ -> heard.running?.close() }
    } +
        // After the grants are heard, so a node answers support only once it is keeping them.
        single { settings: AccessShadowSettings -> ShadowedAccess.of(settings) } +
        single { grants: JdbcGrants, _: GrantsHeard, shadow: bank.api.Shadow -> Rules(grants, shadow) } +
        // Every node publishes what the access log holds and no node has published yet, when Kafka is on.
        single { log: JdbcAccessLog, side: ReadSide ->
            install({ AccessPublishing(side.publishing.publisher?.let { accessPublisher(log, it, side.backend) }) }) { publishing, _ ->
                publishing.running?.close()
            }
        }

// ---- the web ----

/** Who is calling, what they may do, and where their looks are recorded. */
class Callers(val signIn: Signing, val rules: Rules, val looks: JdbcAccessLog, val grants: JdbcGrants, val audit: bank.api.Audit)

/** The access log's outbox to Kafka on this node; nothing when Kafka is off. */
class AccessPublishing(val running: Running<Nothing, Long>?)

private val web: Module =
    singleOf<ActorSystem<Void>>({ ActorSystem.create(Behaviors.empty(), "bank-http") }, { system -> system.terminate() }) +
        // Everything that runs in the background is started before the port opens.
        single { cluster: Cluster, readModels: ActorRef<WhereAreReadModels> ->
            ClusterViews { clusterView(cluster, readModels) }
        } +
        // Every node publishes itself to the `ops` topic, and hears every other (bank spec 0007).
        // After the Prometheus registry, so every gauge set here reaches it.
        single { actors: Actors, cluster: Cluster, node: NodeSettings, _: PrometheusMeterRegistry, side: ReadSide,
            views: ClusterViews, reads: JdbcReadModels, persistence: Persistence, currencies: Currencies ->
            install({
                val latest = ConcurrentHashMap<String, NodeSnapshot>()
                val topic = cluster.topic("ops", NodeSnapshots)
                actors.within {
                    topic.subscribe(
                        spawn("ops-collector", behaviour<NodeSnapshot, Unit>(Unit) { _, _, said -> stay().also { latest[said.node] = said } }),
                    )
                }
                val readers = listOfNotNull(
                    ReadModelNames.STATEMENTS, ReadModelNames.TRANSFERS,
                    ReadModelNames.PUBLISHED_ACCOUNTS.takeIf { side.kafka.enabled }, ReadModelNames.PUBLISHED_TRANSFERS.takeIf { side.kafka.enabled },
                )
                val behind = Behind(persistence.offsets, side.settings.partitions)
                val gauges = bookGauges(currencies, reads, persistence.heads, behind, readers, side.backend)
                val publishing = opsPublisher(NodeMeter(node.name, cluster), topic, side.backend)
                LiveOps(node.name, latest, views, reads, persistence.heads, behind, readers, listOf(gauges, publishing))
            }) { ops, _ -> ops.close() }
        } +
        single { settings: IdentitySettings, rules: Rules, looks: JdbcAccessLog, grants: JdbcGrants, _: AccessPublishing,
            shadow: AccessShadowSettings, reads: JdbcReadModels ->
            Callers(signing(settings), rules, looks, grants, FgaAudit.of(shadow, grants, reads))
        } +
        single { bank: Bank, currencies: Currencies, reads: JdbcReadModels, payrolls: Payrolls, views: ClusterViews,
            health: HealthRegistry, watched: Watched, ops: LiveOps, callers: Callers ->
            bankApi(
                bank, currencies, reads, payrolls, views::current, { asked(health) }, { watched.registry.scrape(OPENMETRICS) }, watched.registry,
                ops::current, callers.signIn, rules = callers.rules, looks = callers.looks, grants = callers.grants,
                audit = callers.audit, telemetry = watched.sdk,
            )
        } +
        singleOf(
            { api: Api, settings: WebSettings, system: ActorSystem<Void> ->
                api.startWithDocs(system, port = settings.port, host = settings.host, docs = docs { docsPath = "/api-docs" })
            },
            { server -> server.stop() },
        )

private fun asked(health: HealthRegistry): Healthy = when (val readiness = health.readiness()) {
    is Health.Up -> Healthy(ready = true, failing = emptyList())
    is Health.Degraded -> Healthy(ready = true, failing = readiness.failing)
    is Health.Down -> Healthy(ready = false, failing = readiness.failing)
}

val bankModule: Module = settings + telemetry + storage + clustered + theBank + access + web

/** The application as a value: the build reads the graph from here, and `main` runs it. */
object LarkBank : LarkApp<PelicanServer>() {
    override val module: Module = bankModule

    override fun AppScope.run(root: PelicanServer) {
        logInfo("Lark Bank on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs")
        root.block()
    }
}
