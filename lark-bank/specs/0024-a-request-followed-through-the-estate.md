# 0024 — A request followed through the estate

## Problem

A transfer is one `PUT` and a dozen hops. The node that takes it asks the shard's node, whose saga asks bank-checks to
screen it over HTTP, then sends a leg to each account's node; each account writes to the journal, the publisher puts
the events on Kafka, and bank-checks' monitoring and access-sync read them back. A slow or failed transfer could have
gone wrong at any of them, and today nothing records which:

- **The bank makes spans and keeps none.** `TracedBank` opens one per transfer, and the SDK it hands it to has no
  exporter: the span ends and is gone. Its ids reach the log lines inside it, and no others.
- **The trace stops at every boundary.** Lark carries it across a fork (Lark spec 0021) but not across a message to an
  actor, local or on another node; Pelican's server continues an inbound `traceparent` but its client sends none; an
  event written to the journal and published to Kafka keeps no trace at all.
- **"Why was this transfer slow?"** is answered from metrics, which say the p99 rose, and from logs, which spec 0023
  joins by `trace_id` only for the lines written inside the first span.

## Not doing

- **The OpenTelemetry Java agent.** It instruments JDBC, Kafka's client and `java.net.http` for free, but costs 50 to
  100 MiB and seconds of start per JVM on the small machines, and knows nothing of Lark's actors, which are most of the
  hops. Spans are made by the libraries the hops belong to.
- **A span per Postgres statement.** The journal's append is one span; what Postgres did inside it is its own metrics.
- **The browser.** The trace begins at the bank's HTTP server, not in the customer's page.
- **Metrics from spans.** Tempo's metrics-generator is off: the RED metrics already come from Micrometer.

## Shape

**Every service exports its spans** over OTLP/HTTP to Tempo, each named by `service.name` (`lark-bank`,
`bank-checks`, `bank-approvals`, `access-sync`, and OpenFGA's own, which it exports itself). One setting each:

```hocon
bank.telemetry { otlp = "http://tempo:4318", otlp = ${?OTEL_EXPORTER_OTLP_ENDPOINT}, sampled = 0.1 }
```

**One trace per request, across every hop**: the node that took the `PUT`, the shard's node, the saga, screening in
bank-checks, both legs on their accounts' nodes, each journal append, the publish to Kafka, and each consumer's
handling of the event. Calls continue the trace; a Kafka consumer's handling is a child of the publish when it handles
one record, and links to each when it handles a batch.

**Sampled at the edge, kept everywhere after.** The node that takes a request decides, one in ten by default; every
hop after follows its parent (`ParentBased`). A trace is whole or absent, never half.

**Tempo, one pod, its blocks in Garage**, a `traces` bucket beside spec 0023's `logs`, seven days.

**Read in Grafana**, linked both ways with spec 0023's lines: a trace's spans open their lines in Loki by `trace_id`,
a line's `trace_id` opens its trace, and the bank's latency histograms carry exemplars, so a point on the p99 panel
opens a trace that made it.

## Why this shape

Spans from the libraries, not the agent: the agent's cost lands on every JVM on every machine, and its blind spot,
Lark's messages, is where a transfer spends its time, so the upstream work is needed either way. Head sampling, not
tail: tail sampling keeps every error and slow trace, but needs a collector holding every span of every trace in
memory until it decides, which three small machines cannot spare at a thousand transfers a second; the alternative
recommended later, if errors go unseen, is to sample 100% of requests that fail at the edge. OTLP straight to Tempo,
not through a collector: one less pod, and the services already batch.

## Depends on

- **Lark spec 0122, "a trace that crosses a message"**: the sender's context carried on `tell` and `ask`, locally and
  across nodes, and the receiver handling the message inside it. Until it lands, a trace ends at the first actor.
- **Lark spec 0123, "an event that keeps its trace"**: the journal keeps each event's `traceparent`, the Kafka
  producer sends it as a header, and the consumer continues it. Until it lands, a trace ends at the journal.
- **Pelican spec 0064, "a client that carries the trace"**: `traceparent` on every call a Pelican client makes. Until
  it lands, screening's calls start new traces in bank-checks.
- **bank-checks** continues an inbound trace in zio-http and exports with the OpenTelemetry SDK, in its own repository.

## Stack

- [x] **`trace-export`** — the SDK with the OTLP exporter and the sampler in the bank; Tempo on Garage, its data
      source, a NetworkPolicy; Pelican's server spans and `TracedBank`'s. Done when: in compose, a transfer's trace in
      Tempo has the server span and the transfer span, and with `sampled = 0` it has none.
      Done: `TraceExportSpec` runs a node against Tempo 3.1 in a container, on compose's own `tempo.yaml`. With
      `sampled = 0`, a `PUT /transfers` under a caller's sampled `traceparent` is in Tempo as
      `PUT /transfers/{transferId}` with `transfer` beneath it; one under an unsampled `traceparent` is not. Tempo 3
      has no `compactor` block: seven days is two flags, its backend worker's and scheduler's. Exported with the JDK's
      HTTP client, not OkHttp. At home, the `traces-store` key is SOPS's to supply, as `backup-store-credentials` is;
      `garage-setup` makes the `traces` bucket for it.
- [x] **`trace-actors`** — on Lark 0122. Done when: on a three-node cluster, one transfer's trace holds spans from the
      node that took it, the shard's node and both accounts' nodes.
      Done: `TraceActorsSpec` sends six transfers on three nodes, each under its own sampled `traceparent`; every
      trace holds `PUT /transfers/{transferId}`, `transfer debitsource`, `account debit` and `account credit`, and
      the traces between them hold spans from more than one node. The bank adds a span per account command and per leg
      sent; Lark carries them. Building it found a gap in Lark: an account answers its leg by reply, and a reply
      answered across nodes lost the trace, fixed there.
- [x] **`trace-calls`** — on Pelican 0064: screening, Approvals and bank-access calls carry the trace; bank-checks,
      bank-approvals and OpenFGA export their spans. Done when: one transfer's trace holds bank-checks' screening span.
      Done, each hop proved on its own rather than in one trace in Tempo: the bank asks the check under the transfer's
      trace id, sampled, its client span the parent (`ScreeningSpec`); the check answers in a server span of that
      parent and exports it (bank-checks' `TracedSpec`). Calls not made through Pelican take `traceHeaders()`: to
      Approvals, and to OpenFGA through bank-access's `Fga`, whose new `carried` supplier sends them; the shadow's
      questions keep the trace on their own thread (`TraceCallsSpec`). Approvals continues and exports its trace
      (its `TraceSpec`, against a stub OTLP endpoint). The saga's screening thread and the shadow's executor took
      nothing from the actor's: both now hand over what it carried. OpenFGA exports over gRPC, so Tempo listens on
      4317 too; tried in compose, its sampler ignores the caller's decision and samples by trace id alone, so it runs
      at the bank's own ratio and keeps the same traces.
- [x] **`trace-events`** — on Lark 0123: published events, and their handling by bank-checks' monitoring, access-sync
      and the bank's grants. Done when: an account opened shows, in one trace, access-sync writing its owner to OpenFGA.
      Done, each hop proved on its own: an account opened under a sampled `traceparent` is on `bank.account-events`
      with that trace's `traceparent` (`TraceEventsSpec`); access-sync writes its owner in a consumer span of that
      trace and sends it on to OpenFGA (bank-access's `TracedSyncSpec`); monitoring checks it in one too (bank-checks'
      `HandledSpec`). Only the trace's own headers are published, not all an append carried. A traced record is
      handled alone, the untraced still together, so a span is a child of its publish and never needs links. Approvals
      publishes its events' trace too, and the bank's grants hear each inside it, so its `applied` call continues it.
- [x] **`trace-views`** — trace to logs and back, exemplars on the latency panels, a runbook section "Following one
      request". Done when: from a 503's line in Loki, one click opens its trace, and from its slowest span, its lines.
      Done: Loki's `trace_id` links to Tempo (spec 0023's source); Tempo's *Logs for this span* asks Loki for the lines
      with the span's `trace_id` and `span_id`; Prometheus keeps exemplars and its source links them to Tempo, and the
      three p99 panels show them. In Grafana 11.2 on the bank's own provisioning, against Tempo 3.1, Loki 3.5 and
      Prometheus 2.54, a browser opened a 503's line, clicked its `trace_id` into the trace, then the slowest span's
      *Logs for this span* into that span's one line. The bank's side of exemplars is `ExemplarsSpec`: a request in a
      sampled trace leaves its trace id on `http_server_request_duration_seconds`, one in an unsampled trace does not,
      and `/metrics` answers in OpenMetrics, the format that carries them. Building it found that the latency was
      recorded after the request's span had stopped being current, so no exemplar could see it: the bank's
      `completedInTrace` filter, just inside the meters', now completes each request inside its trace.
- [x] **`trace-cost`** — the load test at 10% and 100% sampled. Done when: the README has the throughput, p99 and
      memory each costs against tracing off, and the default is set from them.
      Done: `scripts/trace-cost.sh`, each setting on a fresh compose cluster, Tempo up when tracing is on. At 150/s
      for 60 s, twice each, interleaved, every run carried the rate without a failure; the p99s (721 and 956 ms off,
      629 ms and 1.02 s at 10%, 810 ms and 1.05 s at 100%) differ by less than two runs of the same setting do, and
      the nodes' memory did not move; Tempo held 95 MiB at 10% and up to 215 MiB at 100%. The default stays one in
      ten, for Tempo's and network storage's sake rather than the bank's. Measuring it found that the cluster's bank, checks
      and Approvals never turned tracing on (only OpenFGA and access-sync did); each now does, at 0.1, and compose's
      default is 0.1 too, where it was 1.0. This machine fails at 400/s with tracing off, so the cost at the README's
      own rates is not measured here.

## Acceptance

```bash
./gradlew build
scripts/chaos-docker.sh            # compose with the logs and traces profiles
RATE=200 scripts/load.sh transfers # on kind; then a transfer's trace in Grafana's Explore, across four services
```

## Open questions

1. **One in ten?** Recommended as the start, set by `trace-cost`'s numbers; ops can raise it for an hour from the
   setting without a release.
2. **Should every failed request be kept** whatever the sampler says? Recommended yes, at the edge only: a 5xx the
   node that took it answers is marked sampled before its span ends, so its own spans are kept; the hops it already
   made, unsampled, are not. Whole traces of failures need tail sampling, which is a later spec's if this is not enough.
3. **Seven days?** Recommended: traces are for "what happened just now"; a week covers a weekend's incident.
4. **The `trace_id` on the customer's error page**, so support can find it? Recommended yes, in `trace-views`; it is
   an opaque id, and the access fences of spec 0021 still decide who may open it.
