# 0023 — Every log line in one place

## Problem

The estate is nine kinds of process on three machines: three bank nodes, bank-checks, bank-approvals, bank-access and
access-sync, Kafka, Apicurio, two CloudNativePG clusters, Pocket ID. Each writes its log to its own container's stdout
in its own shape, and that log is gone when the pod is replaced. Metrics (spec 0009) say *that* something is wrong;
finding *what* means `kubectl logs` against each pod in turn, before it restarts, and joining the lines by eye:

- **One transfer touches four services**: the bank takes it, bank-checks screens it, the publisher puts it on Kafka,
  monitoring reads it back. Its story is in four logs, with nothing that ties the lines together but a time.
- **The log dies with the pod.** A node that ran out of memory at 03:00 and was restarted has lost the lines that say
  why; the chaos runs in Docker (spec 0009) keep theirs only because the script copies them out first.
- **The lines are for a person at a terminal.** `HH:mm:ss.SSS LEVEL [thread] logger msg {mdc}` in the JVM services,
  ZIO's own shape in bank-checks, Lark's `key=value` annotations at the end: no date, no service, and nothing a query
  can pick a field out of.
- **Alerts point at a runbook, not at the lines.** `AccessDisagreed` (spec 0022) says a question was answered
  differently; which one is in one bank node's log, and the runbook says to go and look for it.

## Not doing

- **Logs as the audit.** Who looked at what is spec 0021's access log, on Kafka and in Postgres, kept for as long as
  the bank keeps accounts. Logs are for operating the bank, are kept for 30 days, and may lose a line under pressure.
- **Traces.** Exporting spans, and carrying a trace across services, actors and Kafka, is spec 0024's. This puts
  `trace_id` on the lines written inside a span, so the two join once 0024 lands.
- **Logs from outside the cluster.** The storage, the router and the machines' own journald stay where they are.
- **Changing what is logged.** Which lines a service writes, and at what level, stays its own; this changes their
  shape and where they go. The one exception is secrets: a line that carries one is a bug, and this spec looks for it.
- **A second Grafana.** Logs are read in the Grafana that already shows the metrics, signed in through Pocket ID (spec
  0021).

## Shape

**Every service writes one JSON object per line** to stdout, with the same names for the same things:

```json
{"ts":"2026-10-02T14:03:11.204Z","level":"WARN","service":"lark-bank","node":"bank-1","logger":"lark",
 "msg":"bank-access disagrees on view-account of acc-1 for eve: the bank says false, bank-access true",
 "trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","transfer_id":"t-91"}
```

`ts`, `level`, `service`, `msg` always; `node`, `logger`, `thread`, `trace_id`, `span_id` when there are any; Lark's
annotations and each library's MDC as fields of their own; a stack trace as one `error` field, not forty lines.
Logback's own `JsonEncoder`-shaped output in the JVM services, the same names from zio-logging in bank-checks.

**The trace id is on every line written inside a span**, from Lark's `logAnnotated`, and is on more of them as spec
0024 carries the trace across actors, calls and Kafka.

**Loki, one pod, its chunks in Garage.** Loki runs as a single binary (`-target=all`), its index and chunks in a
`logs` bucket in the Garage that already keeps the backups (spec 0010), on network storage: the lines outlive the machines,
and nothing is added to them but Loki's own small cache. 30 days, then the compactor deletes them.

**Fluent Bit on each machine**, a DaemonSet reading `/var/log/pods`, sending to Loki with four labels only:
`namespace`, `app`, `pod`, `level`. Everything else in the JSON stays in the line, read by `| json` at query time;
`trace_id` goes as Loki's structured metadata, so finding one trace does not scan a day.

**Read in Grafana**, the Loki data source beside Prometheus:

```logql
{namespace="lark-bank"} | trace_id="4bf92f3577b34da6a3ce929d0e0e4736"
{app="lark-bank", level=~"WARN|ERROR"} | json | msg=~"bank-access disagrees.*"
sum by (app) (rate({namespace="lark-bank", level="ERROR"}[5m]))
```

The bank dashboard gains a logs row: errors by service, and the last hundred `WARN` and `ERROR` lines, each `trace_id`
a link to every line in that trace. Each alert's annotations gain a `logs` link, the query that shows its lines.

**Compose runs the same**, under a `logs` profile: Loki on a local volume and Fluent Bit reading Docker's container
logs, so a chaos run's lines are queried after it, not copied out.

## Why this shape

Loki, not Elasticsearch or OpenSearch: on three small machines, a search engine that indexes every word needs gigabytes
of heap per node to be any use, where Loki indexes the four labels and keeps the lines compressed in object storage the
estate already has; a query by `trace_id` or by service is what the runbook asks, and Loki answers that cheaply. Fluent
Bit, not Grafana Alloy or Vector: Alloy does more than this needs and uses several times the memory, and Promtail, the
obvious choice, is at its end of life. Fluent Bit is a few tens of megabytes per machine and speaks Loki. JSON at the
source, not parsing patterns in the collector: each service knows its own fields, and a pattern in the collector breaks
silently the day a format string changes. The alternative to all of it, keeping `kubectl logs` and adding a sidecar
that copies logs to network storage, keeps the lines but still leaves nothing to query them with.

## Depends on

- **Lark:** `lark-slf4j` already sends Lark's lines to SLF4J, with the annotations as MDC: nothing new. Its
  `logAnnotated` puts `trace_id` and `span_id` on lines inside a span, which this uses as it is.
- **Pelican:** nothing. Carrying the trace on its client's calls is spec 0024's, on Pelican spec 0064.
- **bank-checks, bank-approvals and bank-access** each take the `log-json` change in their own repositories.

## Stack

- [x] **`log-json`** — one JSON object per line from the bank, bank-approvals, bank-access's access-sync and
      bank-checks, with the shared field names; the plain pattern kept for tests, under a `LOG_FORMAT=plain` switch.
      Done when: a test starts a bank node, makes a transfer and a refused request, and parses every line it wrote as
      JSON with `ts`, `level`, `service` and `msg`, and the transfer's lines carry its `transfer_id` and a `trace_id`.
      Done: `LogJsonSpec` in each service. The bank's starts a node, serves Ada and refuses Eve, and parses every line,
      Liquibase's included: its "Running Changeset" and update summary went to stdout as plain text, and now go
      through the log, its own lines bridged from java.util.logging. A transfer writes no line of its own, so the test
      checks that a line inside a span's annotations carries `trace_id` and `transfer_id` as fields; a transfer's
      lines across actors are spec 0024's `trace-actors`. The logstash encoder is 8.1, not 9, which is on Jackson 3.
- ~~**`log-correlation`**~~ — moved to spec 0024, which carries the trace across services, actors and Kafka.
- [ ] **`log-store`** — Loki single binary on Garage's `logs` bucket, 30 days' retention, the Grafana data source, a
      NetworkPolicy (Fluent Bit and Grafana in, Prometheus for its metrics), its pod kept off Kafka's machine, and `LogsNotArriving` and `LokiDown`.
      Done when: on kind, a line written by a pod is found in Loki within 10 s, and is still found after Loki's pod is
      deleted and comes back.
      Built: `deploy/k8s/loki.yaml`, a StatefulSet whose write-ahead log is on its own 2 GiB volume, under a Garage key
      of its own. Run against Garage in Docker with the bank's `setup.sh`: a pushed line, its `trace_id` as structured
      metadata, is found by label and by `trace_id`; still found after Loki is killed (replayed from the write-ahead
      log); and after a graceful stop and an empty volume (read back from Garage). Not yet run on kind.
      `LogsNotArriving` fires only while Fluent Bit (`log-ship`) runs.
- [ ] **`log-ship`** — Fluent Bit as a DaemonSet with the four labels and `trace_id` as structured metadata; the
      compose `logs` profile.
      Done when: after a kind chaos run, every service's lines are in Loki, a node killed mid-run included, and Fluent
      Bit's pods stay under 50 MiB.
      Built: `deploy/k8s/fluent-bit.yaml`, the containerd logs of `lark-bank`, `restore-drill` and `flux-system` from
      each machine, read from their start with offsets kept on the machine, the pods' `app` from a ClusterRole that
      reads pods and nothing else (without it a line loses its namespace and pod as well); and compose's
      `logs` profile, reading Docker's files, with a service's own `service` and `node` as `app` and `pod`. The
      cluster's configuration, run against a Kubernetes API with a pod and its own token, sends a line containerd
      split in two as one, labelled with the pod's `app`, its `trace_id` as structured metadata; compose's sends the
      spec's three queries' lines, sends nothing twice across a restart, and holds 5 MiB. Not yet run on kind under
      chaos.
- [ ] **`log-views`** — the dashboard's logs row, `trace_id` links, a `logs` link on every alert, and a runbook section,
      "Following one transfer".
      Done when: the runbook's queries, run against compose after a load test, return one transfer's lines from three
      services in order.
      Built: the dashboard's Logs row (error lines per minute by service; the last hundred warnings and errors), a
      `logs` annotation on all 21 alerts with the LogQL that shows their lines, the Loki source's `trace_id` linking to
      Tempo, and the runbook's "Following one transfer". Checked in Grafana 11.2 with the bank's provisioning against
      compose's Loki: both panels answer, each line's `trace_id` comes back as the field the link matches; every
      alert's query parses; the runbook's queries, on lines from three services sharing a trace, find the transfer and
      return its lines in order. Not yet run against the services themselves after a load test.
- [x] **`log-hygiene`** — no secret on any line: the chaos script ends by searching every line it caused for bearer
      tokens, session keys, Kafka and database passwords and preshared keys, and fails on any.
      Done when: a token logged on purpose fails the run, and the run without it passes.
      Done: `scripts/log-secrets.py`, run by `scripts/chaos-docker.sh` over every container's log and the load's. It
      takes the secrets from compose's own configuration (each value named as a key, password, token, secret or session
      key), looks for one of ten characters or more anywhere and a shorter one only where a line gives it as a value,
      since the development databases' `bank` is a word every line uses; and looks for anything shaped like a JWT, a
      bearer token or basic credentials. A find is named by file, line and whose secret, never printed. With `LEAK=1`
      the run wrote an ops token from the test issuer through bank-1's own stdout and failed on it, the JWT and its
      `Bearer` header, line 1074, and nothing else; without it, it passed, no secret on any of 4,968 lines.

## Acceptance

```bash
./gradlew build                                       # log-json's test among them
scripts/chaos-docker.sh                               # compose with the logs profile; ends with the log-hygiene search
RATE=200 scripts/load.sh transfers                    # on kind; then the runbook's queries in Grafana's Explore
kubectl -n lark-bank logs ds/fluent-bit | grep -ci error   # 0
```

## Open questions

1. **Which machines' memory pays for it?** Loki single binary at 256 MiB request, 512 MiB limit, on one machine; Fluent
   Bit at 32 MiB request, 64 MiB limit, on each. On spec 0010's 8 GB machines that takes the busiest, Kafka's, from
   about 5,200 MiB to about 5,800. Recommended: keep Loki off Kafka's machine with anti-affinity, and measure under
   the small profile's load before `log-store` is ticked.
2. **30 days, or longer?** Recommended 30: logs are for operating, the access log is the record, and Garage's bucket
   is on network storage, whose space is the backups' first.
3. **Should a service's `DEBUG` ever reach Loki?** Recommended no: Fluent Bit drops `DEBUG` and `TRACE`, and a service
   turned up to debug is read with `kubectl logs` while someone is watching.
4. **Kafka and Postgres's own lines** are not JSON. Recommended: shipped as they are, labelled by `app`, with `level`
   left empty; parsing them is not worth a pattern per version.
5. **Fluent Bit or Grafana Alloy?** Fluent Bit, for memory on small machines; spec 0024's services send their spans
   to Tempo themselves, so nothing needs Alloy's OTLP forwarding. Settled.
