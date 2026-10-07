# 0001 — Money that moves once

## Problem

Lark has sharded entities, a JDBC journal, snapshots and reliable delivery, and Pelican has typed endpoints, but
nothing uses them together at load. Petshop runs one actor on one node. The bank is the service that makes all
of them carry weight at once: many accounts across several nodes, and money that must never be created, lost or
moved twice while nodes come and go.

## Not doing

- No currencies, interest, fees or overdrafts. Money is a `Long` of minor units.
- No authentication. The API is a demo's.
- No two-phase commit. A transfer is a saga, and money in flight is visible as money in flight.

## Shape

- **Account**: an entity sharded by `lark-cluster`, `delivered(persistent(...))` over `JdbcJournal` on Postgres,
  snapshotted every 100 events by `JdbcSnapshots`. It is the only writer for its id, so it takes no locks.
  Commands carry a reference, and the last 256 references are kept in the state, so a retry changes nothing.
- **Transfer**: a saga entity, persisted, that debits the source, then credits the destination, and refunds the
  source if the credit is refused. Every leg is idempotent by transfer id, so a leg that timed out is asked again.
  On restart it reads its next step off its state.
- **Bulk credit**: `sharded.reliable(...)` producers send `Delivered` credits that are confirmed rather than
  answered, so a resend after a shard moves is dropped by the entity's dedup rather than paid twice.
- **Wire**: Protobuf everywhere (`lark-actor-remote-protobuf`; since spec 0005, `lark-actor-remote-kotlinx`, with the data classes as the source). `Protobuf.asked` pairs each request with its
  reply, and the same messages are the journal's bytes.
- The domain (`decide`, `evolve`, the saga's `next`) is pure and tested without an actor.

```kotlin
cluster.sharding(Kinds.ACCOUNT, AccountMessages, passivateAfter = 2.minutes) { id -> account(AccountId(id)) }
bank.transfer(TransferId("t-1"), from = AccountId("a"), to = AccountId("b"), amount = Money(500))
```

## Why this shape

A saga over two single-writer entities keeps every write local to one entity and one journal row, which is what
lets accounts spread across nodes. The alternative is a transfer inside one database transaction over two
account rows. That is simpler, but it puts the lock back and makes the actors decorative.

## Depends on

- **Pelican 0058, a handler that blocks.** Every endpoint asks an entity, and the synchronous binders ran on the
  dispatcher. Until it is released, handlers use `handledByOrFail` on a virtual thread.

## Stack

- [x] **`domain-and-protocol`** — domain, saga, Protobuf schema, codecs.
      Done when: the domain and round-trip tests pass.
- [x] **`entities`** — account and transfer entities, the sharded bank, the bulk producer.
      Done when: a three-node in-process cluster on embedded Postgres moves thousands of random transfers with
      the total unchanged, including across a node leaving mid-run (`ConservationSpec`, `LeavingSpec`).
- [x] **`api`** — the Pelican endpoints over the `Bank` port.
      Done when: the contract tests pass through the typed client.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Should a transfer answer when settled, or at once?** Recommend waiting up to a configured limit (1 s) and
   then answering `Pending`, so the common case needs no polling.
2. **Are 256 recent references enough?** Recommend yes: a retry comes within seconds, not after 256 other
   commands to the same account.
