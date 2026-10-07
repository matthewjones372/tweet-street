# 0004 — A journal on two databases

## Problem

Every node writes every event into one Postgres, so adding nodes does not add write capacity: that one primary is
the bank's ceiling. Lark spec 0088 splits a journal across databases by id; the bank should run on it.

## Not doing

- **No moving accounts between databases.** The list is appended to, never reordered.
- **No read models per database.** The read models and offsets stay in the first database, one set of tables.
- **No measured speed-up.** On one machine two Postgres share the same cores and disk; the gain needs separate hosts.

## Shape

- `bank.database` is the journal's first database, `db-0`, and still keeps the read models and offsets.
  `bank.journal.databases` (`JOURNAL_DATABASES`) adds others as `name=url` pairs, which share its user, password
  and pool size, and are migrated to lark's tables alone (`db/journal.xml`) before anything writes to them.
- The flock's journal is a `ShardedJournal` and its snapshots a `ShardedSnapshots`, over the same names in the same
  order. Accounts prune with `Prune.after(offsets, journal, …)`, so each database waits only on its own readers.
- Each read model runs as one projection per database: `statements@db-0`, `statements@db-1`, and so on, including
  the Kafka publisher. The sweeper reads the read models, so it stays one.
- Two projections write the shared tables at once, which is safe: an account's rows are written only by its own
  database's projection, and `ledger_totals` is changed only by `x = x + ?` updates of one row.
- Docker Compose and Kubernetes run a second Postgres, `postgres-2`, as `db-1`. On Kubernetes the two prefer
  different nodes.

## Stack

- [x] **`sharded-journal`** — settings, migrations, the sharded journal and snapshots, projections per database,
      the tests on two databases, and both deployments.
      Done when: ConservationSpec and LeavingSpec pass on two journal databases, and in Docker 18,000 transfers at
      300/s fail none, conserve the ledger, split the journal about evenly, and put every account event on Kafka.

## Acceptance

```bash
./gradlew build
docker compose -f deploy/docker/compose.yml up -d --wait
docker compose -f deploy/docker/compose.yml run --rm -e SCENARIO=transfers -e RATE=300 load
```

## Open questions

Nothing open. Offsets are now saved per database, so a bank upgraded from one database rebuilds its read models and
republishes its account events to Kafka once; both are idempotent downstream.
