# 0018 — Checks outside the bank: screening before money moves, monitoring after

## Problem

Spec 0015 publishes every event as a contract and took out the bank's own consumer: large movements were to become
"the first check an outside service writes". Nothing reads the topics yet, so nothing shows the contract is usable
from another language.

But a check that reads published events runs after the money has moved: it can flag a transfer, never stop one. A
bank needs both. Some rules must stop money before it leaves (a transfer far over what the account has ever sent);
others watch what already happened, where stopping it would cost more than it saves, or where the rule needs more
than one event.

And a check whose logic lives in code changes only with a deploy. The people who decide what is suspicious, a bank's
operations or risk staff, are not the people who deploy it. They need to write the rules themselves, see what a rule
means and what it would have caught before it goes live, and know who changed what.

## Not doing

- **Screening withdrawals and deposits.** They are commands on the account entity, on the path of every account
  command; screening them is a spec of its own once transfers show what screening costs.
- **Holding a transfer for a person to review.** A screened transfer is approved or declined. A hold means a review
  queue and a transfer that waits on a person, and is a later spec.
- **Rules across events.** A rule reads one transfer or one movement. Velocity ("five withdrawals in an hour") and
  history need state, and a spec of their own once this is proved.
- **Roles and approval workflows.** One kind of user, an admin; every change is recorded with who made it.

## Shape

A service of its own, `bank-checks`, in Scala, built the way `starwars-api` is and deciding with `verdict`. It does
two things with the same rules, which an admin writes in a wizard in the browser:

- **Screening**: the bank asks it, over HTTP, about each transfer before the source is debited, and does as it says.
- **Monitoring**: it reads `bank.account-events` and raises a flag for each movement a rule holds on.

### Screening, in the bank

**A step in the saga.** A transfer screens before it debits:

```
Requested → Screened (approved) → SourceDebited → DestinationCredited
          → Screened (declined) → Rejected      (nothing moved, as when the source refuses)
```

`Screened` is an event like any other step: persisted, then the next step read off the state, so a saga recovered on
another node carries on from it. It records the outcome, the rule and version that decided, the check's evidence, and
whether the check answered at all:

```kotlin
data class Screened(
    val outcome: Outcome,            // Approved or Declined
    val rule: String?,               // the rule that declined it; none when approved
    val version: Int?,
    val evidence: String,            // verdict's account of why
    val answered: Boolean,           // false: the check did not answer in time, and the bank's policy decided
    override val atMillis: Long,
) : TransferEvent
```

A decline ends the transfer `Rejected` with the rule as its reason ("declined by large-transfer, version 3"), which
the pages, the statement and the ledger already treat as nothing moved.

**The call.** `POST /screen` on the check, with the transfer's id, both accounts, the amount (currency and plain
decimal) and when it was requested; the answer is the outcome, the deciding rule and version, and the evidence. The
bank describes the endpoint with Pelican and calls it through Pelican's typed client, on a virtual thread, never on
the entity's own: the answer comes back to the saga as a message, as an account's answer to a leg does. A contract
test in the bank checks its description against the OpenAPI document the check serves.

**The same answer twice.** The check stores each decision under the transfer's id, and a second request for that id
answers the first decision, even if the rules have changed since. So a saga that asks again after a lost answer, or
after moving to another node, gets the decision it would have got.

**When the check does not answer.** Within `bank.screening.timeout` (300 ms), the saga's timer fires and the bank's
policy decides: `bank.screening.whenUnanswered = approve` (the default) or `decline`. Either way `Screened` records
`answered = false`, and it is published, so monitoring sees every transfer screening did not. Approving by default
means an outage of the check never stops the bank; `decline` is for a bank that would rather stop than pay unchecked.
Off (`bank.screening.enabled = false`), a transfer goes straight from `Requested` to the debit, as today.

**The contract grows by one case.** `TransferEvent` in `lark-bank-events` gains `Screened screened = 16`: an addition,
which `buf breaking` and Apicurio's `FULL_TRANSITIVE` allow, and which a consumer built before it skips.

### The domains

Five bounded contexts, each owning its language, its data and its rules, and meeting the others only through a
named contract. Nothing reaches into another context's tables or types.

| context | owns | its one job | lives in |
|---|---|---|---|
| **Ledger** | accounts, transfers, money | move money exactly once, and say what moved | lark-bank (exists) |
| **Policy** | rules and their versions | say what the bank considers suspicious, and who said so when | bank-checks |
| **Screening** | decisions on proposed transfers | say, once and for good, whether a transfer may move | bank-checks |
| **Monitoring** | flags on movements | say which movements the policy would question | bank-checks |
| **Access** | admins and their sessions | say who is changing the policy | bank-checks |

**The language.** Each word means one thing, everywhere in these contexts, their code and their pages:

| term | means | context |
|---|---|---|
| *transfer* | the Ledger's saga moving money between two accounts | Ledger |
| *proposed transfer* | what Screening is asked about: a transfer that has not debited yet | Screening |
| *movement* | money in or out of one account, as an account event says it moved | Monitoring |
| *rule* | a named, ordered condition, over proposed transfers (a *screening rule*) or movements (a *monitoring rule*) | Policy |
| *version* | one immutable text of a rule, validated, with its author and time | Policy |
| *live* | the one version of a rule in force now; a rule may have none | Policy |
| *decision* | Screening's answer for one proposed transfer: *approved*, or *declined* by one rule's version | Screening |
| *flag* | Monitoring's note that one rule's version held on one movement | Monitoring |
| *evidence* | why a rule held or did not, condition by condition | Policy, carried by decisions and flags |
| *declined* / *rejected* | Screening *declines* a proposed transfer; the Ledger then ends that transfer *rejected*, as when an account refuses it | Screening / Ledger |
| *admin* | a person who may change the policy | Access |

"Check" names the service, never a thing in it. "Verdict" names the library, never a decision.

**What each context holds true:**

- **Policy.** A version never changes once stored. Every stored version is valid against its subject's schema, so a
  live rule always runs. A rule has at most one live version. Screening rules are in one total order, which says
  which declines first. Every version records its admin.
- **Screening.** One decision per transfer id, ever: stored before it is answered, and answered again unchanged. A
  decision names the rule version that declined it, or none. Screening reads only the live screening rules.
- **Monitoring.** One flag per (account, sequence, rule, version). A movement is stored and its flags published
  before its offset is committed. Monitoring reads only the live monitoring rules.
- **Access.** No version is stored without an admin.
- **Ledger.** As today, and: a transfer debits only after a decision (or the unanswered policy) approves it, and a
  declined one moves nothing.

**The context map.**

```
          lark-bank-events (published language)                 checks.flags (published language)
Ledger ─────────────────────────────────────────▶ Monitoring ─────────────────────────────────▶ anyone
   │                                                   ▲
   │ POST /screen (open host service, OpenAPI)         │ live monitoring rules
   ▼                                                   │
Screening ◀──── live screening rules ──── Policy ──────┘
                                            ▲
                                            │ who
                                         Access
```

- **Ledger → Monitoring**: the Ledger publishes `lark-bank-events`; Monitoring conforms to it through an
  anti-corruption layer (`bank-events`), which turns the generated classes into Monitoring's `Movement` and nothing
  else. No generated class passes that layer.
- **Ledger ⇄ Screening**: Screening is an open host, its OpenAPI the contract; the Ledger is its customer, and
  translates at its own edge: Pelican's client returns the Ledger's own `Screened`, and nothing of Screening's
  vocabulary enters `bank.domain`.
- **Policy → Screening, Monitoring**: Policy publishes the live rule set for each subject, compiled; the other two
  read it and never write it. The wizard's dry run asks Screening and Monitoring for their recent records through
  their own query interfaces, never their tables.
- **Access → Policy**: an admin's identity, attached to each version.

**Code follows the map.** One sbt module per context, each with the same three layers:

| layer | holds | may depend on |
|---|---|---|
| `domain` | the context's types, invariants and errors: pure Scala, no ZIO, no Kafka, no SQL (verdict's style) | `verdict-core`, zio-schema, zio-prelude |
| `service` | the context's operations: a `trait` and its `ZLayer` (starwars-api's style), and the ports it needs | its own `domain` |
| `adapters` | Postgres, HTTP, Kafka: the ports implemented | its own `service` |

| sbt module | depends on |
|---|---|
| `policy` | — |
| `access` | — |
| `screening` | `policy` (the live-rules port) |
| `monitoring` | `policy` (the live-rules port) |
| `bank-events` | `monitoring` (the anti-corruption layer: generated classes in, `Movement` out) |
| `admin` | `policy`, `access`, and the query ports of `screening` and `monitoring` |
| `app` | all of them: wiring, and the one process they run in |

`screening` and `monitoring` do not depend on each other, and nothing depends on `admin` or `app`: the build refuses
a crossing the map does not draw. Each context keeps its own tables, in a Postgres schema of its own (`policy`,
`screening`, `monitoring`, `access`) in the `checks` database, and its migrations beside it.

### The check service

**Built as starwars-api is.** sbt 2, Scala 3.8, JDK 25; versions in `project/Dependencies.scala`, bundles in
`Libraries.scala`, modules made by `Projects.create`; scalafmt (120 columns), sbt-tpolecat, scoverage and
sbt-native-packager; an `AGENTS.md` with the same rules (comment-light, no scaladoc, a service is a `trait` with a
companion holding its `ZLayer` and a `final private case class` implementation, errors are
`enum E(msg: String) extends RuntimeException(msg)`, typed failures and no `throw` in effectful code). ZIO 2,
zio-http endpoints with their OpenAPI and Swagger UI, zio-config, zio-logging through SLF4J, Flyway and Magnum over
Postgres, zio-test with `assertTrue`, and test names that are sentences.

**One process, many contexts.** The contexts above run in one deployable, `app`, each behind its own module; a
context that needs to scale or deploy on its own can be split out along the same line later, with its port becoming
a call.

**What a rule reads.** A rule is a `verdict` rule over one of two records, and says which:

```scala
// Screening: a proposed transfer, before it moves
final case class ProposedTransfer(from: String, to: String, currency: String, amount: BigDecimal, hourOfDay: Int)
  derives verdict.Schema, zio.schema.Schema

// Monitoring: a movement, after
final case class Movement(account: String, kind: String, currency: String, amount: BigDecimal, reference: String,
  hourOfDay: Int) derives verdict.Schema, zio.schema.Schema
```

The wizard builds its choices from the record's `verdict` schema, so a field added to either is offered by the next
build with no change to the wizard. Amounts are `BigDecimal`, and `BTC 0.1` and `0.10000000` are the same amount.

**Screening decides.** The live screening rules are the declining ones: if any holds on the transfer, it is declined,
by the first in the admin's order, with that rule's evidence; if none does, it is approved, with the evidence that
none held. A decision is stored under the transfer's id before it is answered. The check answers from memory: the
live rules are held compiled, and reloaded when a new version is stored (Postgres `LISTEN`/`NOTIFY`, and a poll as a
backstop), so screening costs one evaluation and one insert.

**Monitoring flags.** `bank.account-events` through zio-kafka, one consumer group, with Apicurio's deserializer
configured as `lark-bank-events`' README says, and the generated classes turned at once into Monitoring's
`Movement` by the `bank-events` layer. `Withdrawn`, `Debited` and `Deposited` become movements; other events, and any kind this build does not know,
pass without a word. Each movement is decided against every live monitoring rule, and each rule that holds is a flag.
At least once: an offset is committed only after its flags are acknowledged and its movement stored.

**Flags are a contract too.** Each goes to `checks.flags`, keyed by account, as Protobuf written by zio-schema's codec,
`@fieldNumber` on every field, its schema in Apicurio under `FULL_TRANSITIVE`:

```scala
final case class Flag(
  @fieldNumber(1) account: String,
  @fieldNumber(2) sequence: Long,      // the event flagged: (account, sequence, rule, version) is unique
  @fieldNumber(3) rule: String,
  @fieldNumber(4) version: Int,
  @fieldNumber(5) severity: String,
  @fieldNumber(6) atMillis: Long,
  @fieldNumber(7) currency: String,
  @fieldNumber(8) amount: String,      // a plain decimal, as the bank's Money.amount
  @fieldNumber(9) evidence: String,
) derives Schema
```

Its `.proto` is written beside it and pinned: a test compiles it with protoc and checks that protoc's classes read
what zio-schema writes, field for field; `buf breaking` holds it to what was published before.

### The wizard

A page served by the check, in steps:

1. **What it is about.** A name, a severity, and what it does: *block a transfer* (screening) or *flag a movement*
   (monitoring, and for which kinds).
2. **The conditions.** Rows of *field, comparison, value*, from what the record's schema allows for each field's
   type: `>`, `≥`, `<`, `≤` for amounts and hours; *is* and *is one of* for text. Rows group into *all of* and *any
   of*, and a group can be negated, nested as deep as the admin likes: every `verdict` rule can be written, and
   nothing else.
3. **Read it back.** The rule in words (`Analysis.describe`), its simplest equal form where `Analysis.simplify` finds
   one, and any validation errors against their rows.
4. **Try it.** On a transfer or movement the admin types in, with `verdict`'s evidence for each condition; and a dry
   run over the last seven days, answering how many it would have declined or flagged, with examples.
5. **Make it live.** A new version, recorded with who and when, which the check uses within seconds. A screening rule
   also shows the share of the last seven days' transfers it would have declined, so an admin sees what making it
   live will stop.

**Test a transfer.** A page of its own, beside the rules: an admin types in a transfer and sees what the check would
decide for it now, across every live screening rule in the admin's order, with each rule's evidence and which one
declines it, if any. It is `POST /screen`'s decision, made the same way and stored nowhere, so it can be asked of
any transfer, as often as wanted.

Each rule's page shows its versions side by side and what each was live for, and the decisions or flags it has made.
Nothing is edited in place: a change, switching a rule off included, is a new version. Every version is validated
against its record's schema before it is stored (`RuleJson.load`, every error returned), so a stored rule always runs.

**The dry run is the rule as SQL.** The check keeps the transfers it has screened and the movements it has read for
seven days, in Postgres. A dry run is `verdict`'s `SqlInterpreter` compiling the draft to a parameterised query over
those tables: the same rule, not a second implementation of it.

**The admin API**, zio-http endpoints with OpenAPI, which the wizard calls and a script could too:

| | |
|---|---|
| `GET /schema/{record}` | the fields a rule over `transfer` or `movement` can read, and the comparisons each allows |
| `GET /rules`, `GET /rules/{name}` | the rules, and one with its versions |
| `POST /rules/validate` | a draft checked: its errors, its words, its simplest form |
| `POST /rules/try` | a draft on one record: held or not, and the evidence |
| `POST /rules/dry-run` | a draft over the last seven days: how many, and examples |
| `POST /rules/{name}/versions` | a new version, live or not |
| `POST /screen/test` | a transfer through every live screening rule: the decision, and each rule's evidence; stored nowhere |
| `GET /decisions`, `GET /flags` | the newest screening decisions and flags, by rule |

**Watched.** Screening latency and decisions per rule, consumer lag and flags per rule, rule changes; for the bank's
Prometheus. Alerts when screening's p99 passes half the bank's timeout for five minutes, and when the consumer's lag
grows for ten. The bank counts transfers screened unanswered, and alerts on any.

## Why this shape

Screening in the saga stops money where it can still be stopped, and records why in the journal, beside every other
step, where it is audited and published like the rest. Monitoring from the events keeps watching what screening lets
through or cannot see, and costs the bank nothing when it is slow or down. One rule language serves both, and the
wizard is a way of writing its values, never a second language that could disagree with the check.

HTTP over gRPC: the call is one request and one answer, which needs neither streaming nor a binary encoding, and HTTP
keeps each side on its own stack: zio-http and zio-schema in the check, Pelican in the bank, and an OpenAPI document
between them. gRPC in ZIO means zio-grpc, which is built on ScalaPB.

Approving when the check does not answer makes the check a guard the bank can lose without stopping; the price is a
window where transfers go unscreened, which `answered = false`, monitoring and an alert make visible rather than
silent. A bank that would rather stop sets `decline`.

## Depends on

- Spec 0015, built: `lark-bank-events` in mavenLocal, both topics published, Apicurio running.
- `verdict`, published locally (`sbt publishLocal`) as `dev.verdict:verdict-core` and `verdict-macros`
  `0.1.0-SNAPSHOT`; built with Scala 3.3, which 3.8 reads. `Analysis`, `Evaluator`, `Validator`, `RuleJson` and
  `SqlInterpreter` are all used as they are.
- Pelican's typed client, as the bank's other endpoints are described: nothing new.
- A Postgres database for the rules, decisions and recent records: `checks`, on `bank-db-0`, as Apicurio's is.
- Spec 0017, for CI and deploys; until the machines exist, it builds on GitHub's runners and runs in Docker beside
  `deploy/docker/compose.yml`.

## Stack

In `bank-checks`:

- [x] **`checks-skeleton`** — the repository, laid out as starwars-api: the build with one module per context and
      the dependencies the map draws, `AGENTS.md` with the language and the layers, scalafmt, CI, and
      `lark-bank-events` and `verdict` from the local repositories.
      Done when: `sbt compile test scalafmtCheckAll` passes; a test decodes a record framed as the bank frames it; and
      an import across a line the map does not draw (screening from monitoring, a generated class outside
      `bank-events`, ZIO in a `domain`) fails the build. Done: each of the three failed the build
      for its own reason (the first two do not compile; the third fails `project/Layers.scala`'s check before
      compiling), and `MovementsSpec` reads a record framed as the bank frames it.
- [x] **`policy`** — rule versions for both subjects in Postgres, validated before they are stored, and the live
      sets reloaded on change.
      Done when: a version naming a field its record lacks is refused with that error, switching a rule off keeps its
      history, and a new version is live in the running service within a second. Done: `PolicySpec` and
      `PostgresVersionsSpec`, the second with the poll at an hour so only `NOTIFY` could bring it in.
- [x] **`screening`** — `POST /screen`, decisions stored and answered again for the same transfer.
      Done when: a declining rule declines exactly the transfers its bounds say (a property test), a second request
      for a transfer answers the first decision after the rules change, and p99 over 10,000 requests is under 20 ms.
      Done: `ScreenSpec` and `ScreeningHttpSpec`; p50 3.9 ms and p99 16.5 ms, 8 at a time over HTTP, Postgres in a
      container.
- [x] **`monitoring`** — movements from `bank.account-events`, flags published, offsets committed after.
      Done when: every account event kind becomes the right movement or none, an unknown case passes, protoc's classes
      read every `Flag` zio-schema writes, and against Kafka and Apicurio a restart mid-run leaves no event unchecked.
      Done: `MovementsSpec`, `FlagSpec` (which fails when a field number is changed), and `AccountEventsSpec`: 50 of 400
      events checked before the stop, all 400 after, and the 196 flags stored and published.
- [x] **`admin-api`** — the endpoints above, with OpenAPI.
      Done when: a test drafts a rule of each kind, reads its words, tries it, dry-runs it, and makes it live through
      the API; and the dry run's count is the evaluator's over the same records. Done: `AdminApiSpec`,
      24 transfers and 11 movements counted alike by the dry run and the evaluator.
- [x] **`wizard`** — the pages, served by the check.
      Done when: a Playwright test writes a screening rule of two groups with a negation, sees it described, tried and
      dry-run, makes it live, and sees the next matching transfer declined. Done: `WizardSpec`, in Nix's
      Chromium: "amount is at least 1000 and not (currency is "BTC" or to is "trusted")", 7 of 10 declined in the dry
      run, then the next large transfer declined and one to the trusted account approved.
- [ ] **`deployed`** — its image, its manifests in the home overlay, its database, metrics and alerts.
      Done when: `kubectl kustomize` renders it, and on kind (`scripts/kind-home.sh --flux`) a rule made live in the
      wizard declines a transfer and another flags a withdrawal. Not yet on kind: the manifests render in the base and the home
      overlay, and `scripts/checks-docker.sh` proves the rest in Docker (the bank's three nodes, Kafka, Apicurio and
      the check: a rule made live through the admin API declined a £900 transfer, which moved nothing, and another
      flagged a £350 withdrawal). The kind run waits on `nix build .#bank-image`, which Maven Central's rate limit
      stopped on this machine, and on bank-checks' CI pushing its image to Zot.

In lark-bank:

- [x] **`screened-event`** — `TransferEvent.Screened`, its place in the saga's states, and `screened = 16` in
      `lark-bank-events`.
      Done when: the domain's tests walk a transfer through approved and declined, `buf breaking` passes the addition,
      and `ContractSpec` round-trips `Screened`. Done: `TransferSpec` walks both, `:events:check` passes the new
      case, and `ContractSpec` round-trips an approved and a declined `Screened`.
- [x] **`screening-call`** — the Pelican description and client, the call off the entity's thread, the timer and the
      unanswered policy, and the settings.
      Done when: against a stub check, a declined transfer ends `Rejected` with the rule as its reason and moves
      nothing; an unanswered one follows the policy with `answered = false`; a saga moved mid-screening asks again and
      gets the same decision; and with screening off, transfers run as today. Done: `ScreeningSpec`, against a JDK
      `HttpServer` stub, covers the first three, and a check that is down with `approve`; every other test runs with
      screening off, as before.
- [x] **`screening-measured`** — the chaos run and the 400-a-second profile with screening on, beside the check in
      Docker.
      Done when: both are in this spec, the ledger conserved, and the check stopped mid-run leaves every transfer
      settled, the unanswered ones counted. Done, against a stand-in check (`bank.load.StandInCheckKt`, in the load's
      image) until the real one runs here. Run 8 of `scripts/chaos-docker.sh` ended conserved in both currencies with
      screening on, and the check stopped for 20 s cost no request; 7,711 of 84,000 requests failed against 6,150
      with it off, and the nodes counted 3,172 transfers screened unanswered (from each node's last start, so fewer
      than all). At 400 a second for 60 s: none failed either way, p50 62 ms and p99 700 ms off, 184 ms and 1.49 s
      on, and 10% of screenings unanswered within 300 ms. The README has both.

## Acceptance

```bash
sbt compile test scalafmtCheckAll                    # in bank-checks
nix develop .#ci -c ./gradlew build                  # in lark-bank
docker compose -f deploy/docker/compose.yml up -d    # the bank and the check; a rule, a transfer declined
scripts/chaos-docker.sh                              # with screening on
```

## Settled

1. **The bank's records: generated Java, or zio-schema's codec?** The generated classes from `lark-bank-events`, used
   from Scala in the `bank-events` layer and mapped into Monitoring's `Movement` at once; zio-schema for everything the check owns (2026-09-30).
2. **Screening, monitoring, or both?** Both: screening in the transfer saga for rules that must stop money,
   monitoring from the events for the rest (2026-09-30).
3. **Screening over gRPC or HTTP?** HTTP: a zio-http endpoint in the check, called through Pelican's client from the
   bank (2026-09-30).
4. **The contexts: one deployable, or a service each?** One, `app`, the contexts kept apart by sbt modules, schemas
   and ports; screening split out later if its latency asks for it.
5. **Where does it live?** A new private repository, `bank-checks`, with its own runner scale set (spec 0017's
   pattern).
6. **When the check does not answer?** Approve, with `answered = false` recorded, published and alerted on;
   `decline` is a setting.
7. **The timeout?** 300 ms.
8. **Who may use the wizard?** Admins named in a SOPS-encrypted secret, a password (argon2-hashed) to a session
   cookie, every version recording who; at home, reachable only on the private network too. OIDC when there is more than one
   admin.
9. **The wizard's front end?** Plain HTML and ES modules served by zio-http; Laminar on Scala.js if the nested rule
   builder proves too much for a plain page.
10. **Kafka in ZIO?** zio-kafka.
11. **verdict?** Published locally with `sbt publishLocal`.
12. **Tests with Kafka and Postgres?** Domain, policy, screening and API tests need no Docker (stores behind traits,
    faked); the stores' own tests and monitoring's, against Postgres, Kafka and Apicurio, are a module outside the
    root aggregate.
13. **How long are records kept for dry runs?** Seven days, pruned daily.
14. **Which rule declines, when several would?** The first in an order the admin sets in the wizard.

All settled 2026-09-30, taking each recommendation.

## Settled while building

In bank-checks:

- **The records a rule reads are Policy's**, `ProposedTransfer` and `Movement` in `checks.policy.domain`: Policy
  validates every version against them, so Screening and Monitoring take them from it. Each has its verdict and
  zio-schema schemas as named givens (`rules`, `codec`): one `derives` clause cannot name two type classes called
  `Schema`.
- **A `platform` module**, not a context, holds what every context's adapters share: Magnum's transactor, a Hikari
  pool, Flyway into a schema of the context's own, the Confluent framing, a small Apicurio client, and a test
  Postgres.
- **Pinned for Scala 3.8:** zio-kafka 3.7.1 and zio-metrics-connectors 2.5.8, since the next of each is built on
  Scala 3.9, whose standard library 3.8 cannot read; zio-schema 1.8.7, zio-http's own, which has `@fieldNumber`.
- **Apicurio's deserializer needs `kiota-http-jdk`**, its registry client's HTTP adapter, which nothing brings in.
- **A version is Live, Draft or Off**, and the latest that is not a draft says whether the rule is live, so switching
  a rule off is a version and keeps the history.
- **Sessions are in Postgres** (`access.session`), and admins' argon2id hashes in `CHECKS_ADMINS`, a Secret; the
  app's `hash-password` makes one.
- **Groups in the wizard are all of, any of, none of or not all of** their rows: a negation is how a group combines,
  not a box beside it.

In lark-bank:

- **The client is warmed at start.** Its first call starts Pekko's actor system and opens a connection, about 450 ms:
  past the timeout, so the first transfer on each node after a start went unscreened, which the first run against the
  real check showed. A `GET /health` to the check at start pays it instead.
- **The stand-in stays** for the measurements (`SCREENING_URL` unset); `scripts/checks-docker.sh` runs the real check
  under compose's `checks` profile.

- **The client is generated in a test, not by the Gradle plugin.** `bank.api.screeningSpec()` describes `POST /screen`;
  `ScreeningClientSpec` generates the client from it with `pelican-codegen` and fails if the committed
  `bank.api.screening.ScreeningClient` differs. `./gradlew :api:test -Pregenerate` rewrites it. The plugin would have
  to come from Pelican's source too, in the build and in Nix, for one file.
- **A failed call decides at once.** A refused connection or an answer the bank cannot read is the same as no
  answer: the policy decides then, rather than when the timer fires.
- **A saga may ask more than once.** The sweeper nudges a saga still screening after `stuckAfter`, and it asks again;
  the check answers its first decision, and the saga takes the first answer that reaches it.
- **`Step.Screen` carries when the transfer was requested**, so the call needs nothing but the step.
- **The HTTP client's pool is sized in `application.conf`**: Pekko's default, 4 connections and 32 requests queued,
  refused most calls at 200 transfers a second, and each refusal was a transfer approved unscreened. 64 and 1,024.
- **A saga asks once while a call is in flight.** A new saga is driven twice at once, by its start and by the nudge
  it sends itself, and asked the check twice until it kept a flag; the timer still ends every wait.
- **The bank times each call**, as `bank.screening.duration`, and counts `bank.transfer.screened_unanswered`.
- **The 300 ms timeout is tight on a shared host.** At 400 a second on four cores, a tenth of calls took longer,
  though the check itself answers in about 2 ms. On the machines of spec 0017, with the check on its own node, that
  is to be measured again before the timeout is changed.
- **The contract test is Pelican's own comparison.** `ScreeningContractSpec` runs `apiChanges` with the bank's
  description as what the caller was written against and the check's document (bank-checks' `openapi.json`, copied
  by `scripts/checks-openapi.sh`) as what the server serves, and fails on any break. It found one: zio-http named no
  operation, where the bank's calls `screen`; the check now names each. Pelican's own default response, its refusal
  envelope, is left out of the comparison: the bank's client reads no failure's body.
