# 0002 — What the journal adds up to

## Problem

The entities answer for one account at a time. A bank also has to answer for all of them: a statement, the sum
of every balance, and proof that no money appeared or vanished. And the events should reach other systems, not
stay in one database.

## Not doing

- No query language over the journal. Each question gets its own table.
- No exactly-once to Kafka. Consumers dedupe by `(account, sequence)`.

## Shape

- **Read models run once in the cluster**: a `cluster.singleton("read-models", ...)` starts the projections when
  it starts and stops them on `Stopping`, so exactly one node writes each read model and a move hands it over.
- **Statements and ledger**: `Projection.follow(journal, "account", ...)`, `groupedWithin(500, 200.milliseconds)`,
  one JDBC transaction per batch. Rows are inserted `ON CONFLICT DO NOTHING`, and totals move only for rows that
  went in, so a replayed batch changes nothing. Only the batch's last `Followed` reaches `runProjecting`, so one
  offset is saved per batch.
- **Conservation**: `GET /ledger` answers `sum(balances) + inFlight == paidIn - paidOut`, where `inFlight` is
  debits not yet credited or refunded.
- **Sweeper**: a transfer projection keeps unsettled sagas in a table, and the singleton nudges any older than
  5 s. A saga whose node died resumes without anyone asking about it.
- **Kafka**: a third projection publishes `PublishedAccountEvent` to `bank.account-events`, keyed by account,
  through lark-kafka's `publishTo` (Lark spec 0087), so its offset is saved only once the broker has every event
  up to it. A `lark-kafka` consumer group on every node reads it back into an `alerts` table for large movements, committing
  through `runCommitting`.
- **Pruning**: snapshots prune with `Prune.after(offsets, "statements", "kafka")`, so the journal stays bounded
  and never loses what a read model has not read.
- Streams run on `lark-stream-actors`, in the application's flock.

## Why this shape

A singleton keeps each projection single-writer without a lock table. The alternative is splitting the feed by
account hash across nodes, which scales further but needs a partition-aware feed that Lark's `JournalFeed` does
not offer. That would be a Lark spec of its own if a single writer turns out to be the limit.

## Stack

- [x] **`read-models`** — statements, balances, ledger totals, the singleton.
      Done when: the ledger balances after the 0001 soak test and after a replay from offset zero.
- [x] **`sweeper`** — the transfer table and the nudge.
      Done when: a saga stranded by stopping its node mid-transfer settles within 10 s (`SweeperSpec` strands one
      in the journal directly: it settles in the sweeper's first rounds).
- ~~**`kafka`**~~ — publisher, consumer, alerts. Moved to spec 0015: its `no-alerts` removed the consumer and the
      `alerts` table, and its `PublishingSpec` proves the publisher on a Kafka in a container, a publisher stopped
      mid-run sending what it had not and skipping nothing.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Is Kafka on by default?** Recommend on in the kind deployment and off in `./gradlew :app:run`, set by
   `bank.kafka.enabled`.
2. **Is a batch of 500 within 200 ms right?** Recommend it as the default, measured under 0003's load.
