# bank-access

Who may do what across the Lark Bank estate, in one place (lark-bank spec 0022): an OpenFGA model, the relationships
derived from events, approvals and Pocket ID's groups, and the client each service asks.

| | |
|---|---|
| `model/model.fga` | the model: people, groups, the bank, accounts and bank-checks' rules |
| `model/model.fga.yaml` | its specification: every relation as the estate relies on it |
| `scripts/apply-model.sh` | the model applied to OpenFGA, by the `access-model` Job, from the image `nix build .#model-image` makes |
| `scripts/server-docker.sh` | OpenFGA on Postgres with preshared keys in Docker, the model applied, a relationship written and checked |

```bash
nix develop -c fga model test --tests model/model.fga.yaml   # or: nix flake check
```

A grant (a supporter of an account, an impersonator of a person) carries its expiry as the `not_expired` condition,
so every check says what time it is. Nothing here is written by hand: `access-sync` derives every relationship from
something already recorded, so the store can be emptied and rebuilt.

The model is changed by pull request, with its tests passing and a reviewer from `admins` (spec 0022, settled 6).

## access-sync

Every relationship in the store, derived from something already recorded (`sync/`): an account's owner from its
`Opened` on `bank.account-events`; support's grants from `bank.approval-events`, written on `Applied` with the expiry
the bank gave them; Pocket ID's group members, read each minute and diffed. `FROM_START=true` reads the account events
again from offset zero, which is how an emptied store is rebuilt; the approval events are always read from the start.
An account event published inside a trace is written in a span of it, its `traceparent` sent on to OpenFGA (lark-bank
spec 0024); `OTEL_EXPORTER_OTLP_ENDPOINT` is where those spans go, none leaving without it or with
`TELEMETRY_ENABLED=false`, and `TRACES_SAMPLED` the share of traces it starts that it keeps, 0.1 by default.

```bash
nix develop -c ./gradlew build     # SyncSpec runs Kafka and OpenFGA in Docker
```

## access-client

What each service asks bank-access through (`client/`), published as `io.github.matthewjones372:bank-access-client`:
`check` and `listObjects` with the model's relations as constants (`Account.viewer`), which a test holds to the model;
one acting as another asked about first; a per-request `Memo`; each check within `Access.BUDGET` (50 ms), and
`Answer.Unanswered` (a 503) when bank-access does not answer in it, never yes; `bank.access.checks`,
`bank.access.unanswered` and `bank.access.check.duration`; and every refusal, and every check by staff or one acting,
on `bank.access-decisions`.
