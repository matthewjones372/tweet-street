# lark-bank-events

The bank's events, as a contract (bank spec 0015): every event of every account and every transfer, published to
Kafka as Protobuf, with the schemas in Apicurio.

| Topic | Key | Message |
|---|---|---|
| `bank.account-events` | account id | `bank.events.v1.AccountEvent` |
| `bank.transfer-events` | transfer id | `bank.events.v1.TransferEvent` |
| `bank.approval-events` | request id | `bank.events.v1.ApprovalEvent` (written by bank-approvals, spec 0019) |
| `bank.access-events` | account id, or the caller's subject | `bank.events.v1.AccessEvent`: each look by staff, and each refusal (spec 0021) |
| `bank.access-decisions` | the object asked about | `bank.events.v1.AccessDecision`: each refusal, and each check by staff or by someone acting (spec 0022), published by bank-access's client |

```scala
resolvers += Resolver.mavenLocal
// the generated Java, used as is
libraryDependencies += "io.github.matthewjones372" % "lark-bank-events" % "0.1.0-SNAPSHOT"
// or the .proto files, for ScalaPB to generate case classes from
libraryDependencies += "io.github.matthewjones372" % "lark-bank-events" % "0.1.0-SNAPSHOT" % "protobuf-src" intransitive()
```

## What a consumer can rely on

- **Order per key.** An account's events arrive in order, and so do a transfer's and a request's. Nothing is
  ordered across keys.
- **At least once.** An event can arrive more than once. `(account_id, sequence)` and `(transfer_id, sequence)` are
  unique: dedupe on them. `(request_id, sequence)` too, with one difference: a request approved and not yet applied
  has its `ApprovalGiven` or `AutoApproved` sent again, same sequence, until its owner says it applied it. The owner
  applies a request id once, and answers `applied` again for one it already applied.
- **Money is exact.** `Money.amount` is a plain decimal string at its currency's scale ("10.50", "0.00012000"):
  `new BigDecimal(amount)`, never a double.
- **Only additions.** Every version of these schemas reads what every earlier one wrote, and the other way round,
  checked by `buf breaking` in the bank's build and by Apicurio's `FULL_TRANSITIVE` rule at run time.
- **Skip what you do not know.** An event added after your build arrives with its `oneof` unset (`EVENT_NOT_SET`).
  Skip it; never fail on it.
- **A breaking change is a new version beside the old**: `bank.events.v2`, on new topics, published from the first
  event while `v1` still has consumers.

## Reading a record

Values are framed as Confluent's and Apicurio's deserializers expect: a zero byte, the schema's 4-byte content id in
Apicurio, the message index (a single zero byte: the topic's message is first in its file), then the Protobuf
message. With Apicurio's `ProtobufKafkaDeserializer`:

```properties
apicurio.registry.url=http://apicurio:8080/apis/registry/v3
apicurio.registry.serde.read-indexes=true
apicurio.registry.serde.read-type-ref=false
# The topic's message: Apicurio's deserializer does not choose it by the message index.
apicurio.registry.deserializer.value.return-class=bank.events.v1.AccountEvent
```

The classes are compiled for Java 17 and later.
