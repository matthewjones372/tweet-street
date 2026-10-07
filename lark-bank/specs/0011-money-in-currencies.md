# 0011 — Money in currencies

## Problem

Money is a `Long` of minor units with no currency. Every account is implicitly in pounds, `toString` assumes two
decimal places, and the API takes and returns bare integers (`"amount": 1250`), which no real bank's API or
statement does. This is the first of a series that makes the bank feel like a real one (the roadmap is below),
and every later spec (interest, fees, overdrafts, FX) needs money that knows its currency. Crypto is included
from the start: `java.util.Currency` covers ISO 4217 only, with no BTC or ETH, so the bank keeps its own registry.

## Not doing

- **FX**, between fiat currencies or crypto. A transfer between accounts in different currencies is refused.
  Converting belongs to spec 0015.
- **Precision finer than the registry allows.** Balances and events are whole units of the bank's precision.
- **Anything on a blockchain.** Crypto here is a balance the bank keeps; nothing is deposited or withdrawn on-chain.
- **Changing an account's currency.** It is fixed when the account is opened.

## Shape

The registry is configuration, read once at start:

```hocon
bank.currencies {
  GBP { exponent = 2, symbol = "£" }
  EUR { exponent = 2, symbol = "€" }
  USD { exponent = 2, symbol = "$" }
  JPY { exponent = 0, symbol = "¥" }
  BTC { exponent = 8, symbol = "₿", kind = crypto }   # satoshi: 21M BTC is 2.1e15, far inside a Long
  ETH { exponent = 9, symbol = "Ξ", kind = crypto }   # gwei: a Long holds ~9.2 billion ETH; wei would hold 9.2
}
```

The domain stays pure. The registry is a value passed in, not a global:

```kotlin
data class Currency(val code: String, val exponent: Int, val symbol: String, val kind: Kind)
class Currencies(all: List<Currency>) { fun of(code: String): Either<UnknownCurrency, Currency> }

data class Money(val minor: Long, val currency: Currency) {
    operator fun plus(other: Money): Money   // same currency is required: mixing is a bug, not a caller's error
    fun toBigDecimal(): BigDecimal           // scale = exponent, exactly
    companion object {
        fun parse(value: String, currency: Currency): Either<InvalidAmount, Money>  // "12.50"; "12.505" GBP refused
        fun of(amount: BigDecimal, currency: Currency, rounding: RoundingMode): Money // for rates, later specs
    }
}
```

- **Accounts:** `Open` names a currency from the registry. Transfers between currencies are refused with
  `CurrencyMismatch`, in the source account's decision, before any money moves.
- **API:** amounts are decimal strings with a currency. They are never JSON numbers, which could pass through a
  float. The integer amounts are replaced outright, since there are no outside clients.
  ```json
  { "amount": { "value": "0.00125000", "currency": "BTC" }, "reference": "r-1" }
  ```
- **Journal:** `Opened` carries the currency code, and so does transfer `Requested`. Other events keep bare minor
  units, since an account's currency never changes. Nothing is deployed, so this is version 1 of the format, and
  `bank.proto` is re-pinned.
- **At start:** the bank refuses to start if the registry is missing a currency the journal's accounts use. A
  currency is never dropped while accounts hold it.
- **Conservation:** the ledger check sums per currency, and every currency must balance on its own.
- **Pages:** the UI shows `£12.50`, `¥1,250` and `₿0.00125000`, from the registry.

## Why this shape

A `Long` of minor units stays the ledger's representation: exact, allocation-free, and 8 bytes on the wire and
in Postgres. The hot-account path does its arithmetic thousands of times a second. `BigDecimal` appears only at
the edges: parsing, display, and later specs' rate calculations, each with an explicit `RoundingMode`
(`HALF_EVEN` unless a spec says otherwise). ETH is held to the gwei, not the wei. The alternative, a `BigInteger`
of wei everywhere, is exact at any precision but costs an allocation per amount, a `numeric` column and strings
on the wire, for precision a bank with no chain connection never uses. With nothing deployed, switching later
changes code, not data.

## Depends on

Nothing.

## Stack

- [x] **`currency-domain`** — `Currency`, `Currencies`, `Money` with its currency, `Open` in a currency,
      `CurrencyMismatch`, and the wire shapes and `bank.proto` to match.
      Done when: the domain tests parse and print GBP, JPY, BTC and ETH, refuse excess decimals, refuse a
      mixed-currency transfer, and show `Long` headroom for ETH at 9 decimals.
- [x] **`currency-api`** — the registry from config, decimal-string amounts, the UI, the load test in two
      currencies, and conservation per currency.
      Done when: `./gradlew build` passes, and a chaos run ends conserved in GBP and BTC.

## Acceptance

```bash
./gradlew build
scripts/chaos-docker.sh        # conserved, per currency
```

## Settled while building

- **Every amount on the wire carries its currency**, code and places, not only `Opened` and `Requested`. An event
  then reads the same whatever the registry says later, and the places the ledger holds are stored with it.
- **The domain's `Currency` is the code and places alone**; symbol and kind are the registry's (`Listed`), since
  how a currency is shown is not part of any amount.
- **A transfer across currencies is refused by the destination and refunded**, not refused before the debit. The
  source cannot know the destination's currency without an extra ask on every transfer; the saga already undoes a
  refused credit, and a transfer's status then says `Refunded` with the reason.
- **One 422 for every amount that cannot move**, the mismatch included (`accountCurrency` names the account's):
  Pelican declares one response per status, and a client acts on both the same way.
- **Alert lines are per currency** (`alertAbove` in the registry): one line in minor units meant nothing across
  pounds and bitcoin.
- `GET /currencies` lists the registry, for the pages' currency picker and symbols.
- The Docker chaos run, transfers in GBP and BTC under every fault, ended conserved in both; 56 tests pass.

## Open questions

Settled: ETH to the gwei, with `BigInteger` the change to make if on-chain deposits ever come; BTC, not XBT; crypto
at full precision on statements, with trailing zeros trimmed on the balance page.

## Roadmap: a bank that feels real

| Spec | What |
|---|---|
| 0011 | money in currencies, crypto included (this) |
| 0012 | the bank's own books: every movement is balanced postings, and the bank has accounts too (cash, fees, interest) |
| 0013 | interest and fees: daily accrual in `BigDecimal`, credited monthly; monthly fees as postings |
| 0014 | overdrafts and holds: an arranged limit, and a balance that is available versus booked |
| 0015 | FX: a quoted rate that expires, fiat and crypto, with the spread booked to the bank's FX account |
| 0016 | account numbers and statements: sort code and account number with a check digit, monthly statements, closing an account |
