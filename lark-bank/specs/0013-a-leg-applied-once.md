# 0013 — A leg applied once, however busy the account

## Problem

A transfer's legs (debit, credit, refund) are retried: the saga resends a leg whose answer it has not had after
`legTimeout` (2 s). A retry is harmless only if the account recognises it, and an account recognises a reference
only while it is among its last 256 (`Account.RECENT`). A busy account passes 256 commands in well under 2 s: at
600 a second, in 0.4 s. A credit applied, whose answer was lost to a node dying, and retried after the account had
moved on 256 commands, would be credited twice. That creates money. The ledger check would show it, but nothing
prevents it.

## Not doing

- **Changing how deposits and withdrawals are recognised.** A caller's own retry comes within moments, and 256
  covers it. Only a saga retries on a timer, after a node has died.
- **An index of every reference ever applied.** That grows with the account's whole history.

## Shape

- **An account holds its open legs.** A `Debited`, `Credited` or `Refunded` adds its reference (`debit:t-1`) to
  `Account.Open.legs`, as well as to `recent`. A command whose reference is in either set is a repeat, and writes
  nothing.
- **The saga closes them.** Once a transfer has settled (Completed, Rejected or Refunded), its saga tells both
  accounts `Close(t-1)`. Each account that holds a leg of `t-1` writes `LegsClosed(t-1)`, which removes them.
  An account with none writes nothing.
- A settled saga that is recovered sends its closes again, and a second close changes nothing. A close that is
  lost leaves the leg open. That costs a few bytes, never a second payment.
- `LegsClosed` moves no money. The statements and the ledger skip it; Kafka carries it, as it carries every event.

## Why this shape

What must be remembered is bounded by transfers in flight, not by the account's throughput. A merchant taking 600
transfers a second, each settling in about 20 ms, holds about a dozen open legs. The alternatives are worse:
- Remembering references for a time window costs memory, and snapshot size, proportional to throughput.
- A unique index on references in the journal is exact, but is a Lark change to every journal.

The cost here is one more event per account per transfer, written in the same append as the account's other
commands when it is busy (spec 0086).

## Depends on

Nothing.

## Stack

- [x] **`legs-closed`** — `legs` on the account, `Close` and `LegsClosed`, the saga's closes, the read models
      skipping them.
      Done when: a debit retried after 300 other commands on the account writes nothing (it debits twice
      before), and a close forgets the leg.

## Acceptance

```bash
./gradlew build
scripts/chaos-docker.sh
```

## Settled while building

- 58 tests pass, and the Docker chaos run ends conserved in GBP and BTC with every transfer settled.

## Open questions

1. **Should closes also go on a timer, in case one is lost?** Recommended: no. A lost close only leaves a leg
   open, which is the safe side, and a recovered settled saga sends them again.
