# bank-approvals — repository layout (draft)

Spec 0019 in lark-bank is the brief. This is the shape of the repository it builds, laid out as lark-bank is (Kotlin on
Lark and Pelican, Gradle, Nix) so a reader of one can find their way in the other.

```
bank-approvals/
├── AGENTS.md                  how to work here: specs first, one stack entry per PR, the build's gates
├── CLAUDE.md                  points at AGENTS.md
├── README.md                  what it is, how to run it, the audit's guarantees in a page
├── flake.nix / flake.lock     JDK 25, Gradle, buf, Chromium for Playwright (as lark-bank's)
├── settings.gradle.kts        the modules below; larkSource / pelicanSource composite builds, as lark-bank's
├── gradle.properties          larkVersion, pelicanVersion, bankEventsVersion
├── build.gradle.kts           Kotlin, detekt, the layering check (domain depends on nothing)
│
├── domain/                    approvals-domain: pure Kotlin, no Lark, no Pelican, no I/O
│   └── src/main/kotlin/bank/approvals/domain/
│       ├── Request.kt         the saga's states and events: Requested … Applied / ApplyFailed, Rejected,
│       │                      Withdrawn, Superseded, Expired; decide(state, command) → events | refusal
│       ├── Votes.kt           separation of duties, distinct approvers, a vote replaced, VoteRefused and why
│       ├── Policy.kt          a kind's approvers, how many, expiry, autoApprove conditions matched on facts exactly
│       ├── Content.kt         the SHA-256 of before, after and facts; what each vote was cast on
│       └── Chain.kt           each event's hash of the one before: the per-request chain, and verifying it
│
├── protocol/                  the wire between nodes: commands and replies as @Serializable, mapped by kimney
│   └── src/main/kotlin/bank/approvals/protocol/
│
├── api/                       approvals-saga (its HTTP half): Pelican descriptions and handlers
│   └── src/main/kotlin/bank/approvals/api/
│       ├── Endpoints.kt       POST /requests, GET /requests/{id}, /approve, /reject, /comment, /withdraw,
│       │                      /applied, /apply-failed; waiting-on-me, asked-by-me, recent; audit queries, exports,
│       │                      GET /approvals/audit/verify
│       ├── Caller.kt          the caller from Pelican 0061, as the bank's (bank spec 0021)
│       ├── Rules.kt           who may vote on what: the policy's groups, never the requester
│       └── Handlers.kt
│   └── src/main/resources/ui/ approvals-pages: waiting on me, asked by me, recent, one request (side-by-side diff,
│                              impact, the timeline), plain HTML and modules as the bank's pages
│
├── app/                       approvals-saga (its running half) and approvals-audit
│   └── src/main/kotlin/bank/approvals/app/
│       ├── Requests.kt        a request as a Lark persistent entity, events versioned (lark 0091)
│       ├── Timers.kt          expiry, and asking the owner to apply again until it answers
│       ├── Policies.kt        policies.yaml read at start and on change; the version on each decision
│       ├── Audit.kt           who and from where on each act; the nightly digest of chain heads, shipped offsite
│       ├── Publishing.kt      approvals-published: bank.approval-events, Protobuf through Apicurio
│       ├── ReadModels.kt      waiting-on-me, by person, by subject, for the pages and the auditor
│       ├── Identity.kt        pelican-oidc: tokens and sign-in against Pocket ID
│       ├── Settings.kt / Wiring.kt / Main.kt
│   └── src/main/resources/
│       ├── application.conf
│       └── db/                Liquibase: read models, the digest table (the journal is Lark's)
│   └── src/test/kotlin/…      a test cluster on Postgres in a container; Playwright for the pages
│
├── policies/
│   └── policies.yaml          who approves what, and when nobody need look: changed by pull request, shipped by
│                              Flux as a ConfigMap
│
├── Dockerfile                 the image, the policies inside it: a change to them is a pull request and a new image
│                              (its manifests are lark-bank's deploy/k8s/approvals.yaml, beside the bank's and the
│                              check's)
│
├── specs/                     this service's own specs from here on: README.md, TEMPLATE.md, 0001-…
└── scripts/                   approvals-docker.sh: request, approve twice, see it applied, verify the chains
```

## What it takes from elsewhere

| From | What | How |
|---|---|---|
| Lark | entities, journal, timers, Kafka, cluster | mavenLocal snapshot, or `-PlarkSource` |
| Pelican | endpoints, pages, pelican-oidc, pelican-test | mavenLocal snapshot, or `-PpelicanSource` |
| lark-bank | `lark-bank-events`, which gains `bank.approval-events` (approvals-published) | `publishToMavenLocal` |
| lark-bank | the test issuer (bank spec 0021), for tests and compose | published as `lark-bank-issuer` beside the events |

The approval events are defined in lark-bank's `events` module, not here: every consumer (the check, the bank, an
auditor's copy) already reads the published language from that one package, and `buf breaking` guards it there.

## Where each stack entry lands

| Stack entry (spec 0019) | Repository | Modules |
|---|---|---|
| `identity` | lark-bank | `deploy/k8s` (Pocket ID) |
| `approvals-domain` | bank-approvals | `domain` |
| `approvals-saga` | bank-approvals | `protocol`, `api`, `app` (entity, timers, policies) |
| `approvals-audit` | bank-approvals | `domain/Chain.kt`, `app/Audit.kt`, the audit endpoints |
| `approvals-published` | lark-bank, then bank-approvals | `events` (schema), `app/Publishing.kt` |
| `approvals-pages` | bank-approvals | `api/src/main/resources/ui` |
| `checks-oidc`, `checks-asks` | bank-checks | its `access` and `policy` contexts |

## Open for you

1. **Its own specs, or lark-bank's?** Recommended: spec 0019 stays in lark-bank as the brief; the repository's
   `specs/` starts at 0001 for what comes after, as bank-checks did.
2. **Publish the test issuer** from lark-bank as `lark-bank-issuer`? Recommended: yes, beside `lark-bank-events`, so
   bank-approvals and bank-checks sign in through the same test provider in their tests.
