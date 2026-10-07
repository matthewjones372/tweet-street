# Runbook

What to do when an alert fires (`deploy/k8s/monitoring.yaml`, bank spec 0009). Each section says what the alert means,
what to look at first, and what is safe to do. Nothing here moves money by hand: the journal is the only record, and
every fix below lets the bank's own sagas finish what they started.

The two numbers to know:

- **The ledger balances when** paid in − paid out = balances + money in flight, in each currency on its own (bank
  spec 0011). `bank_ledger_gap{currency}` is the difference, in that currency. At rest it is 0; while
  transfers move it is only behind by what the read models have yet to project, and settles within seconds.
- **Money in flight** is a transfer that has debited its source and not yet credited its destination or refunded
  it. It is not lost: its saga finishes it, and `bank_transfers_pending` counts them.

## The ledger does not balance

`LedgerDoesNotBalance`: the gap is not 0 while no transfer is pending, for five minutes.

1. Check it is not the read models: `bank_journal_lag` for every database and reader. A read model behind its journal
   shows an old balance, and the gap closes once it catches up. If any lag is above 0, go to
   [a read model is behind](#a-read-model-is-behind) first.
2. With every lag at 0, it is real. Page the owner. Do not restart anything: a restart can hide which node
   projected what. Take `GET /ledger`, `GET /transfers` and the ops page's journal table, and keep them.
3. The journal is correct by construction (one writer per account, sequence numbers checked on append); the read
   models are what add it up. Rebuilding them from the journal is safe: stop the bank, delete the read model rows and
   their offsets for the affected reader, start the bank, and let it project again.

## A journal database is down

`JournalUnwritten`: a command was refused because its journal database could not be written (lark spec 0098).

- The customer saw a 503 and can retry with the same reference; nothing was applied twice.
- On Kubernetes, CloudNativePG promotes the replica. With `dataDurability: required`, writes wait until the old
  primary is back as a replica: expect 503s for that database's accounts until then (bank spec 0008).
- In Docker there is no replica: start the database again. Once it is back, the feed passes the orderings of the
  appends that never committed after `gapTimeout` (10 s) together (lark#294), and the read models catch up.

## The bank answers 503

`BankUnavailable`: more than 5% of requests answer 503 for five minutes.

- A 503 means the bank could not answer in time. The caller retries with the same reference, and it is applied
  once.
- Look at `lark_cluster_unreachable` and `lark_cluster_members`: while a member is unreachable, its shards answer
  nothing until it is back or downed (`stableAfter`, 20 s on Kubernetes).
- Then `JournalUnwritten`, and each node's heap: a node the kernel kills for its memory limit comes back as a new
  life, and its shards move twice.

## A node is unreachable

`ClusterMemberUnreachable`, `ClusterBelowSize`, `NodeDowned`.

- A member unreachable for `stableAfter` is downed by the side that keeps a majority (a `Lease` on Kubernetes). The
  downed node ends its process (`whenDowned = exit`) and is started again by Kubernetes or Docker as a new life
  (lark spec 0097), which joins the cluster that downed it (lark#295). Nothing needs doing unless it keeps happening.
- It keeps happening: look for long GC pauses (`jvm_gc_pause_seconds_max`), a heap near its limit, or the node's
  CPU throttled. A node that cannot answer a probe within `ackWithin` for `stableAfter` is downed however healthy.
- Never restart every node at once to "fix" a split: the journal is safe, but every saga in flight stalls until its
  shard is placed again.

## A read model is behind

`ReadModelBehind`: a reader is more than 10,000 events behind one journal database for five minutes.

- Behind by a lot and not moving: the lag is the furthest-behind partition (spec 0014). Find which in the offsets
  (`select name, last_ordering from lark_offset order by last_ordering`, names like `statements@db-0#2`), and its
  worker's node in the logs (`read models of db-0 partition 2 starting on …`). Check that node is Up, and its log
  for `the feed passes ordering`. One such line per gap is expected after a database outage; they should come
  together, not one every `gapTimeout`.
- Behind and moving: it is catching up. Balances and statements shown are that far behind; money is not.

## Screening is slow or unanswered

`ScreeningSlow`, `MonitoringBehind` or `TransfersScreenedUnanswered` (bank spec 0018). The bank asks the checks about
each transfer and waits 300 ms; unanswered, it approves (`bank.screening.whenUnanswered`), records `answered = false`
on `Screened`, and counts it. Nothing is lost: monitoring still reads every movement after.

1. `kubectl -n lark-bank get pods -l app=checks` and its log: a check that cannot reach `bank-db-0` answers nothing.
2. `checks_screening_duration_ms` on the dashboard: slow with the check healthy means its node is busy; move it.
3. `checks_monitoring_consumer_lag`: behind and growing, the consumer is failing and starting again; its log says why
   (Kafka, Apicurio or the database).
4. To stop screening while it is fixed, set `SCREENING_ENABLED=false` on the bank: transfers debit at once, as before.

## A look went unrecorded

`LookUnrecorded` (bank spec 0021). Every request by someone in a staff group, or acting as a customer, and every
refusal, is written to `access_log` beside the answer; one that could not be written was answered anyway, and is
counted in `bank_access_unrecorded_total` and logged with who and which endpoint. The log is what a customer's "who
has looked" and the auditor rely on, so a gap is an incident to write up, not only to fix.

1. The bank's log: `kubectl -n lark-bank logs -l app=lark-bank | grep 'was not recorded'` names each one and why.
2. Almost always `bank-db-0` refusing writes: see *A journal database is down*.
3. Rows reach `bank.access-events` from the same table, a batch a second, on whichever node gets there first:
   `select count(*) from access_log where published_at is null` growing means Kafka or Apicurio is away; the rows
   wait, and go once it is back.

## Reading the read models at psql

Nobody reads customers' data as `bank` (bank spec 0021): an auditor uses `auditor_ro`, whose password is in
`auditor-ro-credentials`, and row security shows nothing until they say they are auditing.

```bash
kubectl -n lark-bank port-forward svc/bank-db-0-ro 5433:5432 &
PGPASSWORD=$(kubectl -n lark-bank get secret auditor-ro-credentials -o jsonpath='{.data.password}' | base64 -d) \
  psql -h localhost -p 5433 -U auditor_ro bank
bank=> set bank.auditor = on;   -- every account; without it, none
bank=> select * from statement_line where account_id = 'acc-1' order by seq_nr desc limit 20;
```

`auditor_ro` reads the balances, statements, transfers' states and grants, and nothing else: not the journal, not the
access log. A customer's request is read the same way, as `bank_reader` with `bank.caller` set to them, so a query
that forgot its `where` returns their rows only. `bank_reader` and `auditor_ro` are made by CloudNativePG
(`managed.roles` on `bank-db-0`) and granted their reads by the bank's migration at every start. A node that cannot
read as `bank_reader` refuses to start and says so: check `bank` is still in `bank_reader`'s members (`\du`).

## bank-access is down or slow

`AccessDown` or `AccessCheckSlow` (bank spec 0022). bank-access is OpenFGA, two replicas, on its own database
`access` on `bank-db-0`; the model in it is bank-access's `main`, applied by the `access-model` Job each time its
image moves. Every check fails closed, so with both replicas down no one is let in.

1. `kubectl -n lark-bank get pods -l app=bank-access` and their logs: a replica that cannot reach `bank-db-0-rw` waits
   in its `migrate` init container, and says so.
2. Slow with both up: `openfga_datastore_query_count` on the dashboard; a check that walks many relationships is a model
   to look at, not a server to grow.
3. The model: `kubectl -n lark-bank logs job/access-model` says which version it wrote. A model that will not apply
   leaves the last one in place; fix it on bank-access's `main`, whose tests run first.
4. A key refused (401 in a client's log): its preshared key in `access-keys` and the server's differ; the server reads
   them at start, so restart it after the Secret changes.

## bank-access disagrees with the bank

`AccessDisagreed` (bank spec 0022, while in shadow). The bank answered from its own rules, as it still does; bank-access,
asked the same question, answered otherwise, and again two seconds later, so it is not a relationship access-sync had
not yet written. No one was let in or kept out by it, but the flip waits until this is zero.

1. The bank's log says which: `bank-access disagrees on <asked> of <target> for <who>: the bank says <true|false>,
   bank-access <true|false>`.
2. bank-access said yes where the bank said no: a relationship it should not hold. `fga tuple read` on the object (or
   the store's `/read`), then find the event access-sync wrote it from; a grant past its expiry is a clock, not a tuple.
3. bank-access said no where the bank said yes: one missing. An account owner comes from `Opened` on
   `bank.account-events`; a grant from `Applied` on `bank.approval-events`; is access-sync behind, or did it refuse
   the event (its log says)?
4. Staff questions disagree wherever no Pocket ID gives access-sync groups (compose, kind): expected there.

`bank_access_shadow_total{answer="unanswered"}` is questions bank-access did not answer in its 50 ms; they are not
disagreements, but a high share of them is what `AccessCheckSlow` is about.

## Following one request

A slow or failed request is one trace (spec 0024), and Grafana goes between it, its lines and its latency:

- **From a line:** a `503` or an error in the dashboard's Logs row or in Explore on Loki; open it, and its `trace_id`
  is a link to the whole trace in Tempo: every node, actor, call and Kafka hop it took, and where the time went.
- **From the trace:** each span has *Logs for this span*, every line written inside that span (Loki, by the span's
  `trace_id` and `span_id`). The slowest span's lines are usually the reason.
- **From a latency panel:** the dashboard's p99 panels show exemplars, a dot for a sampled request that fell in a
  bucket; a slow dot opens its trace.

Only sampled requests have a trace (`TELEMETRY_SAMPLED`, 10% unless set) and so only they have links; a line from an
unsampled one is still found by its `transfer_id` or its time.

## Following one transfer

Every line written inside a transfer's span carries its `trace_id` (spec 0023), and the line that names the transfer
carries its `transfer_id` too. In Grafana's Explore, on the Loki source (or `curl` to Loki on 3100 in compose):

```logql
{namespace="lark-bank"} | json | transfer_id="t-91"
```

finds the transfer's lines; open one, and its `trace_id` is in its fields. Then every line of that trace, from each
service it reached (the bank's nodes, the checks' screening, access-sync), oldest first:

```logql
{namespace="lark-bank"} | trace_id="4bf92f3577b34da6a3ce929d0e0e4736"
```

The `trace_id` is a link: it opens the same trace in Tempo, with where the time went. In compose, the same query, each
service's lines interleaved in the order they were written:

```bash
curl -sG localhost:3100/loki/api/v1/query_range --data-urlencode direction=forward \
  --data-urlencode 'query={namespace="compose"} | trace_id="4bf92f3577b34da6a3ce929d0e0e4736"' \
  | jq -r '[.data.result[] | .stream.app as $app | .values[] | [.[0], $app, .[1]]] | sort_by(.[0])[] | "\(.[1])  \(.[2])"'
```

Each alert's `logs` annotation is the query for its own lines; the bank dashboard's Logs row has errors by service and
the last hundred warnings and errors.

## Lines are not arriving

`LokiDown` or `LogsNotArriving` (spec 0023): Loki, one pod, keeps every service's lines in Garage's `logs` bucket for
30 days. What it has not yet written to Garage is in its write-ahead log on its own volume, so a restart loses none.

1. `kubectl -n lark-bank get pod loki-0` and its log. Most often it cannot reach Garage (`garage:3900`): check Garage's
   pod and the volume under it, as for [backups](#backups).
2. Loki down loses nothing that is still on the machines: the pods' logs stay in `/var/log/pods` until the kubelet
   rotates them, and the shippers send what they missed when Loki is back.
3. Lines arriving nowhere while Loki is up is the shippers: their pods, and their own log for refused pushes.

## Silencing an alert, and turning on debug

Estate (spec 0026) shows every alert firing with its runbook section here, and anyone in `ops` or
`admins` may act on it there:

- **Silence…** on an alert's card, for a while and with a reason, writes the silence to Alertmanager under your name;
  the reason is what the next person reads. Alertmanager tells no one yet, so a silence hides the alert from the page
  and its count, not from a pager.
- **Turn on debug…** on lark-bank's page sets `lark-bank-logging` to `DEBUG` for 15 minutes or an hour. The bank
  rereads it every ten seconds, and the kubelet updates the mounted file within a minute, so its DEBUG lines start
  within two. Estate sets it back to `INFO` when the time is up; nobody needs to remember.
- By hand, if Estate is down: `kubectl -n lark-bank patch configmap lark-bank-logging -p '{"data":{"level":"DEBUG"}}'`,
  and `INFO` again after.

## Who may reach what

Bank spec 0021's fences, each with what to look at when a service that worked stops reaching another.

- **Kafka**: every client signs in as its service (`bank`, `checks`, `approvals`) with SCRAM-SHA-512, the passwords
  in `kafka-users`, and may do only what `deploy/k8s/kafka/setup.sh` allows it. The broker sets the
  users and ACLs itself at every start and is not ready until it has (`kubectl -n lark-bank exec kafka-0 -- cat
  /tmp/setup.log`). A service's log saying `TopicAuthorizationException` or `GroupAuthorizationException` names a topic
  or group the setup does not give it: add it there, never a wildcard. `SaslAuthenticationException`: its password and
  the broker's differ; both read `kafka-users`, so restart whichever started before the Secret changed. To look as
  the broker does: `kubectl -n lark-bank exec kafka-0 -- /opt/kafka/bin/kafka-acls.sh --bootstrap-server
  127.0.0.1:9094 --list`.

## Backups

`WalArchivingStalled`: a journal database has archived no WAL for fifteen minutes, so point-in-time recovery is
falling behind.

- Each database archives every WAL segment, and takes a base backup nightly at 02:17, to the `bank-backups` object
  store through the Barman Cloud plugin (`deploy/k8s/backups.yaml`). Retention is 14 days.
- Check the plugin's pod in `cnpg-system`, the object store's credentials, and that the store answers.
- To restore a database to a moment: create a new Cluster that bootstraps from the object store, with
  `bootstrap.recovery.source` naming an external cluster that uses the plugin with `barmanObjectName: bank-backups`
  and `serverName` the original cluster's name, and `recoveryTarget.targetTime` the moment. Point the bank's `DATABASE_URL` or `JOURNAL_DATABASES` entry at the new
  cluster's `-rw` service. Restore every journal database to the same moment: a transfer's two legs can be in
  different databases.

## Growing the journal

Which journal database owns which of the 1,024 slices is a table in the primary, `lark_journal_slices` (lark spec
0105), written on first start from the databases listed then. To grow:

1. Add the database to `JOURNAL_DATABASES` and roll the bank. It owns nothing yet.
2. Move a range of slices to it, one range at a time, from a bank pod, which has the driver and the credentials
   (the image built by Nix carries the command):

   ```bash
   kubectl -n lark-bank exec lark-bank-0 -- sh -c 'LARK_JOURNAL_USER=$DATABASE_USER LARK_JOURNAL_PASSWORD=$DATABASE_PASSWORD \
     lark-journal-move move --database db-0=$DATABASE_URL --database db-1=… --database db-2=… --slices 0..255 --to db-2'
   ```

   The first `--database` is the primary, which holds the slice table. Only the moving range pauses, for a few
   hundred milliseconds, while its tail is copied; appends to it answer "try again" meanwhile.
3. After an hour, and once every read model has passed the range on its old database, clean it up there: the same
   command with `clean --read-models statements,transfers --after 1h` in place of `move …`.

A database listed must never be removed while it owns any slice.

Each database's appends share commits (lark spec 0108): whatever appends are waiting go in one transaction with one
commit. It is on by default; `JOURNAL_GROUP_COMMIT=false` turns it off, and each append is then a transaction of its
own. At 400 transfers a second on one small host it took a journal append from about 7 ms to under 1 ms.

## Changing an event

The journal's events and snapshots are versioned (lark spec 0091, `protocol/.../Codecs.kt`): each is written marked
with `JOURNAL_VERSION`, and one written before versioning is read as version 1. A node refuses an event of a
version newer than its own, rather than misread it, so a new shape is rolled out in two releases:

1. **Read it.** Add the new version's codec with an upgrade from the one before, and keep writing the old version
   (`versioned(current = n + 1, …)` reads both; the release still writes version n). Roll it out to every node.
2. **Write it.** Raise `JOURNAL_VERSION` to n + 1. Every node can already read it, so a rolling restart never meets
   an event it cannot read.

Never edit an older version's upgrade or shape: events of that version are in the journal for good. Kafka carries
the unversioned body, as `protocol/schema/bank.proto` describes it; a change there is a change for its readers too.

