package bank.approvals.app

import bank.approvals.api.Approvals
import bank.approvals.api.Signing
import bank.approvals.api.approvalsApi
import bank.approvals.domain.RequestId
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.RequestMessage
import bank.approvals.protocol.RequestMessages
import bank.approvals.protocol.SubjectMessage
import bank.approvals.protocol.SubjectMessages
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcOffsets
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcSnapshots
import io.github.matthewjones372.lark.actor.projection.Projection
import io.github.matthewjones372.lark.actor.snapshots
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.actor.Actors
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.cluster.cluster
import io.github.matthewjones372.lark.app.liquibase.Migrated
import io.github.matthewjones372.lark.app.liquibase.migrations
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.sharding
import io.github.matthewjones372.lark.cluster.spread
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import liquibase.Scope
import liquibase.ui.LoggerUIService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.slf4j.bridge.SLF4JBridgeHandler
import java.nio.file.Path
import javax.sql.DataSource
import io.github.matthewjones372.lark.stream.Actors as StreamActors

// ---- storage ----

/**
 * Liquibase's lines into the log, as JSON like every other (lark-bank spec 0023): its own through java.util.logging,
 * bridged to SLF4J, and what it would print to stdout ("Running Changeset", the summary) through that log instead. Its
 * scope is kept per thread and handed to the threads started after, so this runs as this file's modules are defined,
 * before any node is built.
 */
@Suppress("unused")
private val liquibaseLogged: Unit = run {
    Scope.enter(mapOf(Scope.Attr.ui.name to LoggerUIService()))
    System.setProperty("liquibase.command.showSummaryOutput", "LOG")
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
}

private fun pool(settings: DatabaseSettings): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = settings.url
        username = settings.user
        password = settings.password
        maximumPoolSize = settings.poolSize
        poolName = "approvals"
    },
)

private val storage: Module =
    singleOf({ settings: DatabaseSettings -> pool(settings) }, { pool -> pool.close() }).boundTo<DataSource>() +
        migrations("db/changelog.xml")

// ---- the cluster and its entities ----

class Entities(val requests: Sharded<RequestMessage>, val subjects: Sharded<SubjectMessage>) {
    fun request(id: String): ActorRef<RequestMessage> = requests.entity(id)

    fun subject(id: String): ActorRef<SubjectMessage> = subjects.entity(id)
}

private val clustered: Module =
    actors() +
        single { actors: Actors, data: DataSource, _: Migrated ->
            // Set on the flock before any entity starts: a persistent entity reads its journal from the flock.
            val journal = JdbcJournal(data)
            actors.within {
                journal(journal)
                snapshots(JdbcSnapshots(data))
            }
            JournalReady(journal)
        } +
        cluster("approvals.cluster") +
        single { actors: Actors, cluster: Cluster, settings: EntitySettings, owners: Owners, _: JournalReady ->
            actors.within {
                val subjects = cluster.sharding(Kinds.SUBJECT, SubjectMessages, settings.passivateAfter, settings.shards) { id ->
                    subject(id)
                }
                val requests = cluster.sharding(Kinds.REQUEST, RequestMessages, settings.passivateAfter, settings.shards) { id ->
                    request(RequestId(id), subjects::entity, owners, settings.applyEvery)
                }
                Entities(requests, subjects)
            }
        }

/** The journal set on the flock: what the entities wait for, and what the publishers follow. */
class JournalReady(val journal: JdbcJournal)

// ---- every event published (lark-bank spec 0019) ----

/** The publishers started, spread over the nodes: what the web waits for, so a request's events reach Kafka. */
object Published

/** The owning service asked again on Kafka. */
/**
 * The SDK Approvals' spans are made through (lark-bank spec 0024): W3C's `traceparent` in and out, sampled where a
 * request enters at [TelemetrySettings.sampled] and as its parent was otherwise, batched to Tempo when enabled.
 */
private fun telemetry(settings: TelemetrySettings, web: WebSettings): OpenTelemetrySdk {
    val resource = Resource.getDefault().merge(
        Resource.create(Attributes.of(SERVICE_NAME, "bank-approvals", SERVICE_INSTANCE_ID, "${web.host}:${web.port}")),
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

val kafkaOwners: Module = single { events: ApprovalEvents -> KafkaOwners(events) }.boundTo<Owners>()

private val publishing: Module =
    singleOf({ settings: KafkaSettings -> approvalEvents(settings) }, { events -> events.close() }) +
        single { actors: Actors, cluster: Cluster, ready: JournalReady, data: DataSource, events: ApprovalEvents, settings: KafkaSettings ->
            actors.within {
                val backend = StreamActors(this)
                val heads = RequestHeads(ready.journal)
                val offsets = JdbcOffsets(data)
                // Each a share of the requests, by slice, placed on a node and moved with its offset when that node goes.
                cluster.spread(ApprovalTopic.PUBLISHED, settings.publishers) { share ->
                    Projection.worker {
                        publishedRequests(ready.journal, offsets, events, heads, share, settings.publishers, settings.every).start(backend)
                    }
                }
            }
            Published
        }

// ---- approvals and the web ----

private val web: Module =
    single { settings: PolicySettings -> PolicyFile(Path.of(settings.path)) } +
        single { entities: Entities, settings: EntitySettings, policies: PolicyFile ->
            ShardedApprovals(entities::request, entities::subject, policies::current, settings.askTimeout)
        }.boundTo<Approvals>() +
        singleOf<ActorSystem<Void>>({ ActorSystem.create(Behaviors.empty(), "approvals-http") }, { system -> system.terminate() }) +
        single { identity: IdentitySettings -> signing(identity) } +
        single { settings: TelemetrySettings, web: WebSettings -> install({ telemetry(settings, web) }) { sdk, _ -> sdk.close() } } +
        single { approvals: Approvals, audit: JournalAudit, signing: Signing, policies: PolicyFile, sdk: OpenTelemetrySdk ->
            approvalsApi(approvals, audit, audit, signing, sdk) { policies.current().approverGroups }
        } +
        singleOf(
            { api: Api, settings: WebSettings, system: ActorSystem<Void>, _: Published, _: Digesting ->
                api.startWithDocs(system, port = settings.port, host = settings.host, docs = docs { docsPath = "/api-docs" })
            },
            { server -> server.stop() },
        )

// ---- the audit (lark-bank spec 0019) ----

/** The nightly digest started, once in the cluster: what the web waits for. */
object Digesting

private val auditing: Module =
    single { ready: JournalReady, data: DataSource -> JournalAudit(ready.journal, data) } +
        single { actors: Actors, cluster: Cluster, audit: JournalAudit, settings: AuditSettings ->
            actors.within {
                val backend = StreamActors(this)
                // One worker, placed on a node and moved when it goes: a day is digested once, wherever.
                cluster.spread("digest", 1) { _ -> Projection.worker { digesting(audit, settings.digestEvery).start(backend) } }
            }
            Digesting
        }

/** Everything but how the owning service is asked again, which [owners] says. */
fun approvalsModule(owners: Module = kafkaOwners): Module = settings + storage + publishing + owners + clustered + auditing + web

/** The application as a value: the build reads the graph from here, and `main` runs it. */
object BankApprovals : LarkApp<PelicanServer>() {
    override val module: Module = approvalsModule()

    override fun AppScope.run(root: PelicanServer) {
        logInfo("Approvals on ${root.baseUrl}, docs at ${root.baseUrl}/api-docs")
        root.block()
    }
}
