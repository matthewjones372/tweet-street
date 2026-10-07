# 0016 — Money as a BigDecimal, all the way down

## Problem

`Money` was a `Long` of minor units (spec 0011), turned into a `BigDecimal` only where money left the bank. That
capped what a currency could hold: ETH was kept to the gwei, 9 places, because a `Long` of wei reaches only 9.2
ether, and every currency's largest amount was `Long.MAX_VALUE` of its minor unit. Every boundary (the API, the
pages, and the published events of spec 0015) converted, and each conversion was a place to get the exponent wrong.

Money should be a `BigDecimal` everywhere: in the domain, in the journal's events and snapshots, between nodes and
in the read models.

## Not doing

- **Rounding rules, rates, interest or FX.** Nothing here rounds. `Money.of(amount, currency, rounding)` stays the
  one place a caller rounds, and names how.
- **Carrying the old shapes forward.** The bank has never been deployed, so no journal, snapshot or read model holds
  money as minor units. The shapes change outright: no second journal version, no upgrade, no rollout in steps and
  no conversion changeset.
- **Changing any currency's places.** ETH at 18 is possible now, but is a configuration change of its own.

## Shape

**`Money` holds a `BigDecimal` at its currency's scale, always.** The constructor refuses any other scale, so two
amounts of one currency always compare and add exactly, and `"10.5"` is never a GBP amount (`"10.50"` is):

```kotlin
data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {
    init {
        require(amount.scale() == currency.exponent) { "$amount is not at $currency's ${currency.exponent} places" }
        require(amount.abs() < LIMIT) { "$amount $currency is past the $LIMIT any amount may reach" }
    }

    operator fun plus(other: Money) = Money(amount + same(other).amount, currency)   // exact: same scale
}
```

`Money.parse("12.5", GBP)` pads to `12.50`; `"12.501"` is still refused (more places than the currency has), since
an amount is never rounded on the way in. The `Math.addExact` overflow checks go, since a `BigDecimal` does not
overflow; a bound of 10^30 of any currency takes their place, so one bad input cannot poison a total.
`Money.ofMinor(minor, currency)` stays, for the tests that think in pence.

**On the wire and in the journal, a plain decimal string.** The wire `Money` is `(amount: String, currency)`, the
amount as `BigDecimal.toPlainString()` at the currency's scale. kimney maps every other field; the `Money` pair is
one `Transformer` each way. The pinned `bank.proto` shows `required string amount = 1`.

**Read models: `numeric`.** Every amount column (balances, statement amounts and balances, the ledger's totals,
transfers' and alerts' amounts) is `numeric`, written at the currency's scale. The `exponent` columns stay: they
say the currency's places, which a `Currency` needs when a row, or a total, is read back. The ledger's totals sum
`numeric`s, so they cannot overflow either, and the books hold their gap as a `BigDecimal`.

**The published events (spec 0015)** carry `amount` as the `BigDecimal`'s plain string, straight from the domain.

## Why this shape

A fixed scale per currency keeps a `BigDecimal` behaving like the `Long` it replaces: equality, ordering and
addition exact, and no amount ever held at a different precision from its neighbours. The alternative, a free scale
(`10.5` and `10.50` both allowed), makes `equals` and hash keys disagree with `compareTo`, a trap in a ledger. A
decimal string on the wire, not an unscaled integer and a scale, is what a reader in any language parses without
reassembling it. The cost is allocation, a `BigDecimal` for every amount where there was a `long`, which the last
entry measures.

## Depends on

Nothing new in Lark or Pelican. kimney 0.3.0's `withTransformer`, for the `Money` pair.

## Stack

- [x] **`money-decimal`** — `Money` on a `BigDecimal` at its currency's scale, `parse`, `of`, `format` and every use
      in `domain`.
      Done when: `MoneySpec` covers scale refusal, padding, the bound and exact addition at 18 places.
- [x] **`wire-decimal`** — the wire `Money` as a decimal string, the one hand-written transformer pair, and
      `bank.proto` pinned again.
      Done when: every account and transfer event, the account snapshot and every message between nodes round-trip,
      and protoc's classes read the amount as `"5.00"`.
- [x] **`read-models-numeric`** — `numeric` amounts, and the read models writing and reading `BigDecimal`s.
      Done when: the conservation spec, three nodes and two journal databases, ends conserved.
- [x] **`measured`** — the profiling run repeated on this code.
      Done when: the rate and p99 at 400 transfers a second are here, beside the `Long` code's.

## Acceptance

```bash
./gradlew build
scripts/chaos-docker.sh
```

## Settled

1. **Scale: fixed per currency, or free?** Fixed: every amount is held at its currency's scale, so `equals` and
   `compareTo` agree.
2. **The bound?** 10^30 of any currency, the one limit, replacing `Long.MAX_VALUE`'s.
3. **Postgres type?** `numeric` with no precision: each row keeps its currency's scale, and no column-wide limit has
   to be chosen.
4. **A rollout in steps?** No: the bank has never been deployed, so the shapes change outright.

## Settled while building

- **The `Long` code, measured first** (2026-09-28, Docker on one 4-core host shared by three bank nodes, two journal
  databases, Kafka and the load generator; JDK 25, the Nix image): 400 transfers a second for 90 s, 36,000
  requested, 32,657 ok and 3,343 failed; p50 1.34 s, p99 4.29 s; the ledger conserved. Postgres's backends waited
  mostly on `LWLock:WALWrite`, flushing commits: a journal append averaged 7.3 ms on the first database and 5.7 ms on
  the second, a read-model write about 0.15 ms. The JVM's own time was flat, its GC pauses under a millisecond, and
  no virtual thread pinned after start.
- **The `BigDecimal` code, measured the same way:** 36,000 requested, 31,964 ok and 4,036 failed; p50 1.34 s, p99
  4.33 s; the ledger conserved. Within the run-to-run noise of the `Long` code's numbers: the journal's appends, at
  6.3 and 7.1 ms, still set the rate, and a `BigDecimal` per amount costs nothing that shows. Lark spec 0108 (appends
  that share a commit) is what goes after the appends.
