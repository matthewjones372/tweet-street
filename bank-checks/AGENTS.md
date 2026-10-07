# AGENTS.md

How to work in bank-checks: the checks outside lark-bank (bank spec 0018), built as starwars-api is.

## The contexts

Four bounded contexts, one sbt module each, meeting only through named contracts:

| module | owns | its one job |
|---|---|---|
| `policy` | rules and their versions | say what the bank considers suspicious, who said so when, and who agreed |
| `screening` | decisions on proposed transfers | say, once and for good, whether a transfer may move |
| `monitoring` | flags on movements | say which movements the policy would question |
| `access` | sign-ins and sessions | say who is changing the policy, as the identity provider vouches for them |

`bank-events` is the anti-corruption layer between the bank's events and Monitoring, and between Approvals' events and
Policy: the only module that sees a generated class. With approvals on, a version that would change what is in force
is asked about in Approvals (lark-bank spec 0019) and waits; it is in force once Approvals gives it, for the content
the check asked about, and never if its request ends another way. `admin` is the admin API and the wizard; `app` wires everything into one process.

The build draws the context map: `screening` and `monitoring` depend on `policy` and never on each other, and
nothing depends on `admin` or `app`. An import across a line the map does not draw does not compile.

## The language

Each word means one thing, in code, pages and messages. A *transfer* is the bank's; Screening is asked about a
*proposed transfer*. A *movement* is money in or out of one account. A *rule* has *versions*, and at most one is
*live*. Screening's answer is a *decision*: *approved*, or *declined* by one rule's version. Monitoring's note is a
*flag*. *Evidence* is why a rule held, condition by condition. An *admin* changes the policy: whoever the identity
provider (Pocket ID at home) puts in one of the admins' groups, `risk` and `admins` unless configured otherwise.
"Check" names the service, never a thing in it; "verdict" names the library, never a decision.

## The layers

Inside each context, three packages:

- `domain`: its types, invariants and errors. Pure Scala: verdict, zio-schema and zio-prelude only. The build fails
  a domain that imports ZIO (`project/Layers.scala`).
- `service`: its operations, a `trait` with a companion holding its `ZLayer`, and the ports it needs.
- `adapters`: Postgres, HTTP and Kafka, implementing the ports. Each context keeps its tables in a Postgres schema of
  its own, and its Flyway migrations beside it (`src/main/resources/db/<schema>`). The app never migrates: `migrate`
  (`checks.app.Migrate`) runs them all before it starts, and the app refuses to start while any is pending. A new
  migration is a new file; one already applied is never edited.

## Style

- Comment-light, as starwars-api: a comment says why, in a line, and only where a reader would get it wrong.
  No scaladoc, banners, `TODO`s or commented-out code.
- Scala 3 syntax, scalafmt authoritative (`.scalafmt.conf`, 120 columns).
- A service is a `trait`, its companion holds its `ZLayer`, its implementation is a `final private case class`.
- Errors are `enum E(msg: String) extends RuntimeException(msg)`, one case per failure. Typed failures; no `throw`
  in effectful code, no `.get` outside tests.
- Money is `BigDecimal`; text columns are `TEXT`.

## Tests

zio-test, `object XSpec extends ZIOSpecDefault`, `assertTrue`, and test names that are sentences. A bug fix lands
with the test that fails without it. Postgres and Kafka tests run in containers.

## Build

`nix develop -c sbt compile test scalafmtCheckAll` must pass before anything merges. It needs `verdict` published
locally (`sbt publishLocal` in verdict) and `lark-bank-events` in mavenLocal (`./gradlew :events:publishToMavenLocal`
in lark-bank). Versions live in `project/Dependencies.scala`, never inline in `build.sbt`.

## Docs

- **Docs say what is, not how it got here.** The README and `docs/` describe the current state: what it does, how
  to run it, what it carries now. No history: no earlier runs, fixed gaps, dated logs or PR trails; measurements are
  the latest only. Why belongs in the spec, what changed in the commit. Diagrams are mermaid fences, checked to draw
  before committing. lark-bank's `docs/services.md` describes this service: keep it true.
