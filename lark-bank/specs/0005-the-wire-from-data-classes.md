# 0005 — The wire from data classes

## Problem

`bank.proto` was the source of everything the bank stores and sends. `protoc` generated Java classes from it, and
170 lines of hand-written mapping turned them into the domain's data classes and back. Every field was written three
times, and a field missed in the mapping compiled.

## Not doing

- **No annotations on the domain.** It stays Arrow and nothing else; the wire shapes are their own classes.
- **No reading of journals written before this.** The bytes changed shape; a bank upgraded from before resets its
  data. The demo has no data worth keeping, and lark spec 0091's `versioned` is the way if one ever does.
- **No proto3.** kotlinx's generator writes proto2; the bytes are the same, so a proto3 reader parses them.

## Shape

- **`bank.protocol.wire`** holds `@Serializable` data classes, one per domain shape, case for case and name for name,
  with money and ids as the `Long` and `String` they hold. They are the source. A `@SerialName` only renames a
  message in the schema, where two would share a name.
- **kimney** derives every crossing between the domain and the wire shapes, both ways, at compile time:
  `fun AccountEvent.toWire(): wire.AccountEvent = transformInto()`. A case or a field on one side and not the other
  stops the build.
- **`Kotlinx.oneOf` tables** (lark spec 0093) give each sealed shape fixed tags: events, snapshots, requests, replies,
  bulk credits and the Kafka record. Each value is a protobuf message with a `oneof`.
- **`protocol/schema/bank.proto`** is `Kotlinx.proto(...)` of the tables, pinned. `SchemaSpec` fails when it
  differs from what the classes make, and `protoc` compiles it in the tests: its classes read what the bank writes, and
  write what the bank reads.
- A request carries the domain's command, `null` for a balance enquiry; an answer is the domain's `Either`.

## Stack

- [x] **`wire-from-data-classes`** — wire shapes, kimney mappings, tables, the pinned schema, and the app on them.
      Done when: every event, snapshot, command, answer and Kafka record round-trips; `protoc`'s classes read the
      bank's bytes and the bank reads theirs; the cluster specs pass; and in Docker 18,000 transfers at 300/s fail
      none, conserve the ledger, and put every account event on Kafka, where a large withdrawal still alerts.

## Acceptance

```bash
./gradlew build
```

## Open questions

Nothing open. `Kotlinx.asked` takes a single class, so a request that is one of a table is written by hand in
`Codecs.kt`; an overload of `asked` over a table in lark would remove those lines.
