# 0027 — A daily withdrawal limit

## Problem

An account pays out as much as its balance holds, as often as it is asked. A stolen session, or a script gone wrong,
empties it in one afternoon. Real banks cap what an account pays out in a day; this one does not.

## Not doing

- **Changing an account's limit after it opens.** It is fixed at opening, like its currency.
- **Limits per channel**, card against transfer: one limit covers every withdrawal and every transfer's debit.
- **A rolling 24 hours.** The day is the UTC calendar day of the event's time.
- **Publishing the limit to Kafka.** `bank.events.v1.Opened` is unchanged; the limit is the journal's and the
  account's.

## Shape

- **The limit.** `Open` takes an optional `dailyLimit` in the account's currency. Without one it is 10,000.00 in
  that currency (10000 JPY, 10000.00000000 BTC). `Opened` records the limit the account opened with, so a later
  change to the default never changes an open account; an `Opened` written before limits, without one, reads as
  the default.
- **What counts.** A `Withdraw` or a transfer's `Debit` is refused when what the account has paid out on the UTC day
  of `now`, plus the amount, would be over its limit. `Withdrawn` and `Debited` add to the day's total; a
  `Refunded` on the same day takes its debit back off, since the money came back. Deposits, credits and refunds are
  never refused for the limit.
- **The refusal.** A new `AccountError`, written to nothing:
  ```kotlin
  data class DailyLimitExceeded(val id: String, val limit: Money, val remaining: Money, val requested: Money)
  // "Account acc-1 may pay out 10000.00 GBP a day and has 250.00 GBP of that left today, not 300.00 GBP"
  ```
  It is checked after the balance: an account that cannot cover the amount says that first.
- **A transfer** whose debit is refused is `Rejected` with that message as its reason, as any refused debit is.
- **API.** `PUT /accounts/{id}` takes `"dailyLimit": "500.00"`, in the account's currency. A withdrawal over the
  limit is a 409 tagged `daily_limit_exceeded` (and the balance's 409 now `insufficient_funds`), with `limit`,
  `remaining` and `requested` as amounts, and the message.
- **State.** `Account.Open` holds its limit, the day it last paid out, what it paid out that day, and that day's
  open debits, so a refund knows whether its debit counted. All are optional on the wire, so a snapshot written
  before limits still reads; `bank.proto` is re-pinned.

## Why this shape

The rule is the account's, decided in `decide` from its own state and `now`, so it is a domain test with no clock.
Counting only the day's total, not every payout, keeps the state small whatever the account's throughput. Optional
fields at the end of each shape keep the journal at version 1: nothing old is reread differently.

## Depends on

Pelican spec 0063 entry 2 (#206, `a2024349`): the client reads a failure's tag. A withdrawal now has two 409s,
`insufficient_funds` and `daily_limit_exceeded`, told apart by the body's `kind`. The server half was already in
the pinned Pelican; `flake.lock` moves the pin to the commit with the client half.

## Stack

- [x] **`daily-withdrawal-limit`** — the limit in the domain, journal, wire and API.
      Done when: the domain tests show the default, a chosen limit, the UTC day, refunds given back, and the
      refusal's message; the round trip carries the new error and fields; `./gradlew build` passes.

## Acceptance

```bash
./gradlew build
```

## Open questions

- Does a refund give back the limit its debit used? Recommended: yes, on the same UTC day; a refund on a later day
  changes nothing, since that day's total never held the debit.
- Is a limit of zero allowed? Recommended: yes, an account that only takes money in; a negative one is refused.
