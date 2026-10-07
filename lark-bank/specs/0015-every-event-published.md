# 0015 — Every event published, as a contract other services build on

## Problem

The bank is to be a core that other services build around, as Thought Machine's Vault is: fraud checks first,
written by other teams and in other languages (the first in Scala). They need the bank's events, and a contract for
them that does not come from the bank's code.

Today the bank publishes only account events, to one topic, in its own binary codec, which only Kotlin sharing
`bank.protocol` can read. Transfer events are not published at all. And the only consumer is the bank itself: it
reads its own topic back to write large movements to `movement_alert` (spec 0002), which a read model could do
without Kafka.

## Not doing

- **Any consumer.** The fraud service is designed later, and outside this repo. Nothing here decides what a check
  returns or what the bank does with it.
- **Checks the bank defines** (Vault's smart contracts). Deferred to a later spec.
- **Kafka as the source of truth.** The journal stays in Postgres. Kafka receives each event after it commits: an
  outbox with Kafka as its sink.
- **A release.** The events package is a snapshot in mavenLocal, as Lark and Pelican are today.
- **Another transport.** Kafka is the one `Publisher`; the interface only keeps the door open.

## Shape

**Two topics, one per aggregate**, each keyed by its id so that order holds where the bank guarantees it:

| Topic | Key | Events |
|---|---|---|
| `bank.account-events` | account id | `Opened`, `Deposited`, `Withdrawn`, `Debited`, `Credited`, `Refunded`, `LegsClosed` |
| `bank.transfer-events` | transfer id | `Requested`, `SourceDebited`, `Rejected`, `DestinationCredited`, `CreditRefused`, `SourceRefunded` |

A fixed partition count from the start (12), never changed: changing it moves keys between partitions and breaks
per-key order for anyone mid-stream.

**Protobuf, one message per topic**, a `oneof` of its aggregate's events, with the envelope every consumer needs
to dedupe and order:

```protobuf
// events/src/main/proto/bank/events/v1/account.proto
syntax = "proto3";
package bank.events.v1;

message AccountEvent {
  string account_id = 1;
  int64 sequence = 2;          // the event's place in the account; (account_id, sequence) is unique
  int64 at_millis = 3;
  oneof event {
    Opened opened = 10;
    Deposited deposited = 11;
    Withdrawn withdrawn = 12;
    Debited debited = 13;
    Credited credited = 14;
    Refunded refunded = 15;
    LegsClosed legs_closed = 16;
  }
}

message Money {
  string currency = 1;         // ISO 4217, or the bank's own code (BTC)
  string amount = 2;           // a BigDecimal, plain and at the currency's scale: "10.50", "0.00012000"
}
```

**Money is a `BigDecimal`.** Protobuf has no decimal, so `amount` is the decimal as a plain string
(`BigDecimal.toPlainString()`, never exponent notation), at the currency's own scale: two places for GBP, eight for
BTC. A consumer reads it with `new BigDecimal(amount)` in Java or `BigDecimal(amount)` in Scala, exact, with the scale
kept, and needs no currency table. The bank writes it from `Money.toBigDecimal()`, which the domain already has.

**The schemas as a package.** A new `events` module holds the `.proto` files and publishes
`io.github.matthewjones372:lark-bank-events:0.1.0-SNAPSHOT` to mavenLocal: the `.proto` files, and the Java
generated from them. The bank maps its domain events to the generated classes. A Scala service takes either:

```scala
resolvers += Resolver.mavenLocal
// the generated Java, used as is
libraryDependencies += "io.github.matthewjones372" % "lark-bank-events" % "0.1.0-SNAPSHOT"
// or the .proto files, for ScalaPB to generate case classes from
libraryDependencies += "io.github.matthewjones372" % "lark-bank-events" % "0.1.0-SNAPSHOT" % "protobuf-src" intransitive()
```

**Full transitive compatibility.** Every version of a schema reads what every earlier version wrote, and the other
way round. Full, because the bank and its consumers deploy in either order; transitive, because a bank's events are
replayed from the start (an audit, a check trained on years of history, a consumer rebuilding itself), so a record
written years ago must still decode. In Protobuf that means only adding: new fields and new `oneof` cases on new
numbers; nothing removed, renumbered or retyped; a field no longer written is `reserved`, its number never reused.

It is checked twice. In the build, `buf breaking` compares the schemas against the last published version, which was
itself checked against the one before. At run time, Apicurio's compatibility rule on both schemas is
`FULL_TRANSITIVE`, so a schema that breaks any earlier version is refused before the bank can publish with it.

**Consumers skip what they do not know.** Under full compatibility an old consumer receives `oneof` cases added
after it was built, as an unset case. The contract says to skip such an event, never fail on it; the package's
README states it.

**A breaking change is a new version, beside the old.** `bank.events.v2` and new topics, published beside `v1` for as
long as `v1` has consumers. The journal is the truth, so `v2` topics are published from the first event, and a `v2`
consumer misses no history. The bank's own journal events are versioned separately (Lark 0091) and mapped to the
contract, so they change without touching it.

**Apicurio holds the schemas at run time.** The bank registers both schemas on start (a no-op when they are already
there) and writes records in the Confluent-compatible framing (a magic byte and the schema's id), which Apicurio's
and Confluent's Scala and Java deserializers read. Apicurio keeps its schemas in Postgres: a third database on the
read models' cluster, backed up with it.

**Publishing behind an interface**, with Kafka its one implementation:

```kotlin
interface Publisher : AutoCloseable {
    /** Sends [record]; the future completes once the transport has it for good (for Kafka, acks=all). */
    fun publish(record: Outbound): CompletableFuture<Unit>
}

data class Outbound(val stream: String, val key: String, val value: com.google.protobuf.Message)
```

The two publishing read models follow the journal as today's does: an offset saved only once everything up to it is
acknowledged, so a restart resends and never skips. Delivery is at least once; `(account_id, sequence)` and
`(transfer_id, sequence)` are what a consumer dedupes on.

**The bank's own consumer goes.** `AlertsConsumer`, `movement_alert` and `GET /alerts` are deleted; the bank only
publishes. Large movements become the first check an outside service writes.

**Kafka at home: one broker.** The home overlay gains a single-broker Kafka (KRaft, about 1 GB) and Apicurio, with
`KAFKA_ENABLED=true`. Losing the broker's machine pauses publishing, not the bank; the outbox catches up when the
broker is back.

## Why this shape

Publishing from the journal keeps Postgres the one truth and makes Kafka a copy anyone can lose and rebuild. Protobuf
with a registry gives a contract other languages generate from, with compatibility checked twice: in the build
(`buf breaking`) and at run time (the registry's rules). Apicurio over Confluent's registry because it stores in
Postgres, which is already run and backed up, and is Apache-licensed. A topic per aggregate over one topic keeps the
only ordering that exists; a topic per event type would lose an account's order. The alternative to publishing at
all, a feed the bank serves over HTTP or gRPC from the journal, needs no broker, and stays open behind `Publisher`.

## Depends on

Spec 0016 (money as a `BigDecimal`), so `amount` comes straight from the domain. Nothing in Lark or Pelican:
`lark-kafka` already produces, and the read models already follow the journal by
partition (spec 0014).

## Stack

- [x] **`events-schemas`** — the `events` module: `account.proto`, `transfer.proto` and the `money.proto` they share,
      the generated Java (for Java 17 and later), `buf breaking` in the build, and `publishToMavenLocal`.
      Done when: `./gradlew :events:publishToMavenLocal` installs the jar with the `.proto` files and classes, and
      renumbering, removing or retyping a field each fail `./gradlew build`. Done: the jar holds the three `.proto`
      files and the classes, at class version 61; each of the three changes failed `:events:check` with buf's
      reason, and adding a field passed.
- [x] **`events-mapping`** — each domain event to its Protobuf message and back, in `protocol` (`Contract.kt`).
      Done when: a round-trip test covers every account and transfer event type, and one test fails if a new
      domain event has no mapping. Done: `ContractSpec`, which failed naming `LegsClosed` when that sample was taken
      out.
- [x] **`publisher`** — `Publisher`, the Kafka implementation with Apicurio's framing, registration on start, and
      both topics published from the journal, replacing today's publishing read model.
      Done when: against Kafka and Apicurio in containers, a transfer's events arrive on both topics, decode with
      Apicurio's own deserializer, and a publisher restarted mid-run resends and never skips; and registering a
      schema that breaks the published one is refused by `FULL_TRANSITIVE`. Done: `PublishingSpec`, on Kafka 3.9.1
      and Apicurio 3.3.3 in containers.
- [x] **`no-alerts`** — `AlertsConsumer`, `movement_alert` and `GET /alerts` removed, the pages and docs with them.
      Done when: the bank has no Kafka consumer, and the chaos run's Kafka freeze still ends conserved. Done: run 7
      of `scripts/chaos-docker.sh` (README) ended conserved in both currencies, and Kafka frozen for 30 s cost no
      request, where it cost 696 before.
- [x] **`kafka-at-home`** — one broker and Apicurio in the home overlay, and their memory in spec 0010's table.
      Done when: `kubectl kustomize deploy/overlays/home` renders them, and the memory table still fits 8 GB. Done:
      the machine holding them comes to about 4,800 MiB.

## Acceptance

```bash
./gradlew build
./gradlew :events:publishToMavenLocal
scripts/chaos-docker.sh
kubectl kustomize deploy/overlays/home >/dev/null
```

## Settled

1. **Timestamps?** `int64 at_millis`, as the journal keeps it: one less import for every language.
2. **Money?** A `BigDecimal`, as a plain decimal string at the currency's scale, straight from the domain once spec
   0016 lands.
3. **Account ids on transfer events?** Both, `from_account` and `to_account`, on every transfer event, so a consumer
   joining transfers to accounts need not keep each transfer's `Requested`.
4. **Partitions?** 12 on each topic, fixed.
5. **Where the snapshot goes?** mavenLocal only, until a second machine builds against it.

## Settled while building

- **`Money` is a file of its own**, `money.proto`, which both topics' schemas import, and which is registered in
  Apicurio first and referenced by name. Two copies in one package would not compile.
- **The bank writes the framing itself**, with no Apicurio serializer: a zero byte, the schema's 4-byte content id
  (Apicurio 3's default, and Confluent's), the message index (one zero byte: each topic's message is first in its
  file), then the message. Apicurio's deserializer reads it with `read-indexes=true` and `read-type-ref=false`, and
  must be told the class (`apicurio.registry.deserializer.value.return-class`): it does not choose the message by its
  index. The package's README says so.
- **Apicurio refuses a breaking version with a 400**, naming each break ("Field type changed ... before: int64,
  after: string"), not a 409. Both are a refusal, and the bank will not start.
- **The baseline `buf breaking` checks against** is `events/published.binpb`, a buf image of the schemas as last
  published, rewritten by `publishToMavenLocal` once it has checked them. It is committed, so CI checks against it.
- **The build needs buf and protoc from Nix**: `nix develop .#ci` names both, and the Nix package gives Gradle
  nixpkgs' `protoc`, since Maven's binary cannot run in Nix's sandbox.
- **Each transfer event names both its accounts** (settled 3), though the domain names them only on `Requested`.
  The publisher remembers them from that event, and reads the transfer's first event back from the journal for one
  requested before it started.
- **Topics are made by the bank** at start, 12 partitions, with `bank.kafka.replication` copies (1 at home).
- **Kafka's log is on a volume**: the publishers save their offsets once the broker has an event, so a broker that
  forgot would leave a gap nobody resends.
- **Apicurio's database** is `apicurio` on `bank-db-0`, a CloudNativePG `Database`; in Docker, a Postgres of its own.
  223 MiB idle.
- **Publishing costs latency on a small host.** 90 s of transfers at 200 a second, no faults, three nodes, Kafka
  and Apicurio on one 4-core machine: none failed either way, but p50 was 315 ms publishing and 126 ms not, p99
  2.06 s and 1.40 s. `KAFKA_ENABLED=false docker compose ...` turns it off. These figures were taken while Kafka's Docker healthcheck started a JVM every 3 s,
  which held Kafka at 170% of the machine at idle; measured again with it fixed (2026-09-30), publishing at 200 a
  second gave a p50 of 54 ms and p99 of 948 ms (README).
- **The alert lines went with the alerts**: `alertAbove` is gone from the currencies, and the live ops page (spec
  0007) no longer lists alerts.
