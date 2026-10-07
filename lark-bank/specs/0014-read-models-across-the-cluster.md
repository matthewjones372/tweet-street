# 0014 — Read models across the cluster

## Problem

The read models run as one singleton (spec 0002). The oldest node follows every journal database's feed, one
projection per database and read model, one batch and one transaction at a time. However many nodes the bank has,
statements, the ledger and transfers' status fall behind once the journal outpaces that one node. Spec 0004
split the journal across databases so that writes grow with them; reads should grow with the nodes the same way.

## Not doing

- **The sweeper.** It reads the read models, not a feed, and still runs once in the cluster, in the singleton.
- **Ordering across accounts.** Each account's events are still read in order. Two accounts in different partitions
  have no order between them, as two in different databases already have none.
- **Changing the partition count while running.** A new count starts every read model from the lowest offset it
  overlaps. The writers are idempotent, so that costs time, not correctness.

## Shape

- **Partitions by slice (lark spec 0106).** Each journal database's feed is split `bank.readModels.partitions` ways
  (default 4) by the ids' slices. A worker follows one database's partition and runs its statements, transfer
  status and Kafka publishing, each saving its own offset as `statements@db-0#2`.
- **Spread over the nodes.** `cluster.spread("read-models", databases × partitions)` places the workers as evenly as
  the nodes allow. A worker whose node leaves or is downed starts on another from its saved offsets.
- **Writers that can run at once.** Partitions write concurrently, and after a range of slices moves between
  databases (lark spec 0105), the same account's events can reach two of them. So:
  - the ledger's totals add only the statement lines a batch inserted, whose key decides who counts an event;
  - a balance or a transfer's status is only ever moved forward (`where last_seq < excluded.last_seq`);
  - rows are written in one order, currencies and ids sorted, so two batches never wait on each other in a circle.
- **Pruning** waits, for each account, on the partition that follows it.
- **Lag**, on the ops page and in the gauges, is each read model's furthest-behind partition.

## Why this shape

Workers per database and partition keep spec 0004's rule, that each database has its own order, and add the
partitions within it. Running one database's three read models in one worker keeps the worker count at
`databases × partitions`, not three times that. The alternative, one singleton per database, gives each database
one node, and no more.

## Depends on

- Lark spec 0106 (a projection across nodes), merged, and Lark's `Slices.partition` and partitioned `Prune.after`
  (lark #305), merged.

## Stack

- [x] **`partitions`** — the partitioned read models, their workers spread, the sweeper alone in the singleton,
      pruning and lag by partition.
      Done when: the conservation spec, three nodes and two journal databases, ends conserved.
- [x] **`writers-at-once`** — totals from the lines inserted, forward-only upserts, one write order.
      Done when: one batch written by eight writers at once is counted once (eight times before).

## Acceptance

```bash
./gradlew build
scripts/chaos-docker.sh
```

## Settled while building

- The conservation spec found that statements can trail transfers' status for a moment, now that they are separate
  projections, partitioned apart and placed on different nodes. The ledger is conserved at every commit, so it
  does not show this. The spec now waits for the statements to agree with the accounts; over six full builds,
  they did so within 7 to 214 ms.
- The Docker chaos run (network cut, a 20 s freeze and a downing, a journal database stopped, Kafka frozen, a
  rolling restart) ends conserved in GBP and BTC, with nothing in flight. Every error logged matches a fault.
- Before the writers changed, one batch written by eight writers at once counted the ledger eight times.
