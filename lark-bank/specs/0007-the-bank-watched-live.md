# 0007 — The bank watched live

## Problem

When the bank runs under load or chaos, what happens is spread across curl calls: `/cluster` for membership,
`/ledger` for conservation, `/metrics` for throughput, `psql` for the journal's split, and `kafka-get-offsets` for
Kafka. Grafana has the metrics, but only on kind, and not the bank's own story: which node runs the read models,
where the shards are, whether the ledger still balances, and how far each database's projections are behind. A demo
of a node being killed is a person reading numbers out of five terminals.

## Not doing

- **No replacement for Grafana.** No history beyond the page's own last few minutes, and no alerting.
- **No controls.** The page watches. It does not kill nodes, run load or change settings.
- **No login.** Like 0006, for a demo on a trusted network.

## Shape

- **Every node publishes a snapshot of itself once a second** to a cluster topic, `ops` (lark spec 0082):
  - its status in its own view;
  - its shards and entities by kind, from what lark's gauges already count (0081);
  - its asks per second, and their p99;
  - whether it runs the read-models singleton.
- **The node serving the page** subscribes to the topic, and adds what one node can answer for all:
  - the ledger: balances plus in flight, against paid in less paid out, and whether it balances;
  - transfer counts by outcome;
  - each journal database's head against each projection's offset in it;
  - the newest alerts.
- **`GET /ops/stream`** is a Pelican `sse<OpsSnapshot>()` endpoint, one frame a second.

```kotlin
val opsStream = endpoint { get("ops" / "stream"); sse<OpsSnapshot>(keepAlive = 5.seconds) }
```

- **`/ops`** is a page with an `EventSource` on that stream, laid out as panels:
  - **Nodes:** one card each, colour for status, with its shards, entities, asks and p99. A node that stops
    publishing greys out after three seconds.
  - **Ledger:** the two sides of the check, and a line of the gap over the last five minutes. It is zero while
    nothing moves, and a red banner shows if it ever fails to balance at rest.
  - **Flow:** transfers per second by outcome, and account commands by outcome.
  - **Journal:** events per database, and projection lag per database.
  - **Alerts:** the newest large movements.

## Why this shape

A topic means one connection gives the whole cluster, from whichever node the browser reached, and it still works
when that node dies: the page reconnects through the Service to another. The alternative is the page polling every
node's `/metrics`. That needs each node reachable from the browser, and a Prometheus text parser in JavaScript.
Server-sent events rather than polling, because Pelican has them and the stream is one-way. Recommended: the topic
and one stream.

## Depends on

- **Pelican: pages served beside an API** (Pelican spec 0059), as 0006.
- **Nothing else.** Topics are lark 0082, the gauges 0081, and `sse` is Pelican's already.

## Stack

- [x] **`ops-snapshot`**: the snapshot each node publishes, the `ops` topic, and `GET /ops/stream`.
      Done when: on a three-node test cluster, one node's stream carries all three nodes' snapshots within two
      seconds, and a stopped node's snapshots stop.
- [x] **`ops-page`**: `/ops` with the five panels on the stream.
      Done when: a Playwright test sees three node cards and the ledger balance at rest, and sees a card grey out
      when its node stops.
- [x] **`ops-demo`**: `scripts/demo.sh` runs load and chaos in Docker with the page open, and the README says how.
      Done when: during `chaos` the page shows a node leave and return, and the ledger balance once load stops.

## Acceptance

```bash
./gradlew build
docker compose -f deploy/docker/compose.yml up -d --wait && open http://localhost:8080/ops
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **Every second, or faster?** Every second.
2. **Lag in events, or in time?** In events: a database's newest ordering against each projection's saved offset.
3. **Per-account data on the stream?** No. Totals, and the alerts the bank already exposes.
4. **A chart library?** None. Inline SVG.

Settled while building:

- **The page is `/ops`,** served by Pelican from `ui/ops.html` like 0006's pages.
- **The ledger's badge says "Settling" while money moves.** The read models trail the journal under load, so the
  two sides differ for a moment; "Not balanced" is kept for a gap at rest, as the banner is.
- **The ask p99 comes from a distribution summary.** lark's `timed` records milliseconds as a histogram, which
  Micrometer keeps as a summary, not a timer. OpsSpec now makes asks and expects a rate and a p99.
- **The demo found two faults.** One was the bank's, and is fixed: a transfer whose start was lost with a killed
  node answered 500, where it should say 503, send again. The other is Lark's: a node back on the same address can
  be downed as the one removed. The README records both, and the demo waits for the removal before it restarts
  the node.
