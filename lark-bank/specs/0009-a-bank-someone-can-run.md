# 0009 — A bank someone can run

## Problem

The bank balanced its books in every run, but nobody would know if it stopped: no alert watched the ledger, the
journal databases had no backups, and the one person who knew what a 503 or a downed node meant was the one who
built it. A chaos run in Docker (a partition, a frozen node, a journal database stopped, Kafka frozen, a rolling
restart) found what a runbook would have had to say.

## Shape

- **Gauges the alerts can watch**: `bank_ledger_gap`, `bank_transfers_pending` and `bank_journal_lag{database,
  reader}`, read at most every ten seconds whatever the scrape rate.
- **Alerts** in Prometheus's own rules, checked by `promtool`: the ledger at rest, a journal that refuses writes, a
  503 rate, unreachable and downed nodes, read models behind, ask latency, full mailboxes, stalled WAL archiving.
- **Backups**: every WAL segment and a nightly base backup of each journal database, to an object store through
  CloudNativePG's Barman Cloud plugin; MinIO in the namespace on kind.
- **A runbook**, `docs/runbook.md`, one section per alert.
- **`scripts/chaos-docker.sh`**: the Docker run of faults under load, ending in the books at rest.

## Settled while building

- The first chaos run found four faults, none of them lost money: the read models stalled behind gaps (lark#294),
  a restarted seed formed a second cluster (lark#295), a saga failed when an account region's mailbox was full and
  left its money in flight (the saga now defers the leg to its timer), and a node the kernel killed for its memory
  (the heap is now half the container, with generational ZGC).
- Later runs found two more. A node thawed with a full region mailbox stopped its cluster actor and stayed out of
  the cluster, serving, as a zombie (lark#299, spec 0101); this was the "bank-2 gone" of run 3. And actor failures
  during a database outage were thousands of stack traces on stderr rather than log lines (lark#300, spec 0102).
- A 2 s pool connection timeout, to fail appends sooner, was tried and reverted: it failed the recoveries that follow
  a shard move.
- Nothing here is verified on Kubernetes: kind does not run where this was built.
