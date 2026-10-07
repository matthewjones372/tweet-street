# Lark Bank

An event-sourced bank that runs as a cluster, built on [Lark](https://github.com/matthewjones372/lark) and served
by [Pelican](https://github.com/matthewjones372/pelican). It is one of four services: [the services](docs/services.md)
says what each does and how they talk, and [how the bank works](docs/how-the-bank-works.md) draws the bank's inside.

```bash
./gradlew build                    # in `nix develop .#ci`: the domain, the protocol, three-node clusters in one JVM
docker compose -f deploy/docker/compose.yml up -d       # three nodes, Postgres, Kafka; profiles add the other services
scripts/up.sh                      # the same on kind
scripts/load.sh transfers          # Proofload as a Job in the cluster; the report lands in build/reports
```

The API and its docs are at `localhost:8080/api-docs`, the customer pages at `localhost:8080/`, the ops page at
`localhost:8080/ops`, and Grafana at `localhost:3000`. Running it in production is the [runbook](docs/runbook.md).

## What it does

Customers open accounts, pay in and out, and transfer between accounts. Money is exact: whole minor units in the
account's currency, at the places the bank's registry gives it, so pounds to the penny and bitcoin to the satoshi.

- **One writer per account.** An account is an actor that owns its balance. It decides each command with the pure
  domain and writes the events before it answers. A command carries a reference, so a retry moves the money once.
- **A transfer is a saga.** Its own entity debits the source, credits the destination, and refunds the source if
  the credit is refused. A saga whose node dies carries on from its last event elsewhere; a sweeper wakes any that
  stop.
- **The books balance.** `GET /ledger` answers `sum(balances) + in flight == paid in - paid out`.
- **Everything is published.** Every account and transfer event goes to Kafka in the contract in `events/`.
- **Every caller is known.** Customers and staff sign in through OIDC; support sees an account only under a grant
  approved in bank-approvals, and the customer sees each look.

## Modules

| Module | Holds |
|---|---|
| `domain` | accounts, money in currencies, the transfer saga as a pure state machine (Arrow only) |
| `protocol` | the wire shapes, mapped to and from the domain by [kimney](https://github.com/matthewjones372/kimney), and the published contract's mapping |
| `events` | the Protobuf contract on Kafka, published as `lark-bank-events` |
| `api` | the endpoints, their declared refusals, the handlers (Pelican) |
| `app` | entities, the cluster, read models, Kafka, sign-in, the application graph |
| `issuer` | a test OIDC issuer for compose, kind and the tests |
| `loadtest` | five [Proofload](https://github.com/matthewjones372/proofload) scenarios, and a stand-in screening check |

| Lark module | What it does here |
|---|---|
| `lark-cluster`, `lark-cluster-kubernetes` | SWIM membership, nodes found through the pods API, a `Lease` for splits; accounts and transfers sharded; read models spread over the nodes; the sweeper a singleton |
| `lark-actor` | every account and transfer is a persistent actor, with snapshots every 100 events and up to 64 commands per append |
| `lark-actor-journal-jdbc` | the journal on Postgres, split by account across two databases |
| `lark-actor-projection` | the journal followed into read models, a batch per transaction |
| `lark-actor-remote(-kotlinx)` | asks and tells between nodes, Protobuf from Kotlin data classes |
| `lark-stream`, `lark-kafka` | the projections, the sweeper, and publishing with offsets saved once Kafka acknowledges |
| `lark-app` and its modules | the application as a checked graph: settings, migrations, the cluster, release order on SIGTERM |
| `lark-micrometer`, `lark-otel`, `lark-slf4j` | metrics, traces across every hop, JSON log lines |

## What it carries

Three nodes as containers on one 4-core, 16 GB machine, with Postgres committing durably, every event published, and
the load generator on the same machine, so these are a floor:

| Run, 60 s | Failed | p50 | p95 | p99 |
|---|---|---|---|---|
| `transfers` at 200/s | 0 | 54 ms | 489 ms | 948 ms |
| `transfers` at 400/s | 0 | 62 ms | 367 ms | 700 ms |
| `transfers` at 400/s, screened | 0 | 184 ms | 1.16 s | 1.49 s |
| `hot`, 600/s at one account | 0 | 5 ms | 22 ms | 39 ms |

At one CPU and 768 MiB a node (`scripts/small-docker.sh`, the size of a small machine), it carries 200 transfers a
second with no failures and a p99 of 108 ms.

What tracing costs (`scripts/trace-cost.sh`, bank spec 0024): the same 150 transfers a second for 60 s on a fresh
cluster with tracing off, at 10% sampled and at 100%, each twice, on a shared 4-core machine that carries 150/s
cleanly and fails at 400/s:

| Tracing | Failed | p50 | p99 | Each node | Tempo |
|---|---|---|---|---|---|
| off | 0, 0 | 54 ms, 61 ms | 721 ms, 956 ms | 1.05–1.11 GiB | — |
| 10% | 0, 0 | 40 ms, 59 ms | 629 ms, 1.02 s | 1.06–1.14 GiB | 95 MiB |
| 100% | 0, 0 | 60 ms, 45 ms | 810 ms, 1.05 s | 1.04–1.12 GiB | 96 MiB, 215 MiB |

At this rate tracing costs nothing that shows above the noise between two runs of the same setting: every run
carried the full rate, and the nodes' memory is the same. What grows is Tempo, and what it keeps in its bucket, ten times
over at 100%. So the bank, the checks and Approvals sample one request in ten (`TELEMETRY_SAMPLED=0.1`), in the cluster
and in compose; a request that arrives in a caller's sampled trace is always traced.

Under `scripts/chaos-docker.sh` (a node cut off, a node frozen, a journal database stopped, Kafka frozen, two nodes
restarted, and the check stopped when screening is on, all under 200 transfers a second) the ledger balances at rest. A frozen or cut-off node
costs its shards' requests until it is downed and they move, about 20 s; a stopped journal database costs half the
accounts' writes until it is back; a graceful restart costs nothing. Every failure is a 503 or a timeout, safe to
retry with its reference. It ends by searching every line the run caused, each container's and the load's, for a secret
(`scripts/log-secrets.py`: compose's passwords, keys and session key, and anything shaped like a token), and fails on
any; `LEAK=1` writes a real token to a node's log first, to show the search finds one.

## Versions

Lark `0.7.1-SNAPSHOT`, Pelican `1.0.1-SNAPSHOT`, bank-access-client `0.1.0-SNAPSHOT`, Proofload `0.1.0-rc4`,
Kotlin 2.4.10, JDK 25. The snapshots are unreleased; the build takes them from Maven local or from checkouts beside
this one:

```bash
./gradlew -PlarkSource=../lark -PpelicanSource=../pelican -PaccessSource=../bank-access build :events:build
nix build .#bank                                         # the same, pinned by flake.lock
```
