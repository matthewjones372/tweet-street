# 0012 — Two regions, both open

## Problem

The bank runs in one place. A second datacentre today could only be a standby: it takes no writes until someone
promotes it, and an unplanned failover can lose the last acknowledged movements (the discussion that led here).
The aim is active/active: two regions each taking writes all the time, each surviving the other's loss for
everything that is its own, with no global database and no WAN round trip on an ordinary payment.

## Not doing

- **Any account written in two places.** An account is one writer; it lives in one region, its home.
- **Automatic takeover of a lost region's accounts.** That is a promotion of its replica, a human decision with a
  runbook, as for a standby. It is a later spec.
- **Synchronous replication between regions.**
- **More than two regions.** The shape allows it, but nothing here tests it.

## Shape

- **An account's home is in its id:** `north-acc-1`, `south-acc-7`. It is fixed at opening, as a sort code
  fixes a branch, so no lookup is needed to find it. Opening an id whose region the bank does not know is refused.
- **Each region is a whole bank:** its own Lark cluster, its own Postgres (journal, read models, offsets), and
  its own Kafka topic. `bank.region = "north"`, and `bank.regions.south.join = …` names the other.
- **Routing:** a request for an account at home is served as today. One for the other region's account is asked
  of that region's entity through a federation (Lark 0103), and answered as if local. Clients should call an
  account's home region, which makes that path rare.
- **Transfers:** the saga runs in the source account's region, and the transfer id carries it: `north-t-…`. The
  debit is local. The credit to a `south-` account crosses through the federation. If south is unreachable, the
  saga stays `Debited`, with the money in flight, and its timer and the sweeper retry the leg until south answers.
  A refused credit is refunded as today. Legs are idempotent by transfer id, so a retry that reaches south twice
  credits once.
- **Books:** each region's ledger has two new totals, `sentAbroad` (debits whose credit is in the other region)
  and `receivedFromAbroad`. A region balances as paid in − paid out − sent abroad + received from abroad =
  balances + in flight. `GET /ledger/global` asks both regions and checks that what one sent the other received,
  per currency, once nothing is in flight.
- **Surviving the other region's loss:** each region's accounts, local transfers, statements and pages keep
  working. Only transfers to the lost region wait, with their money in flight, never lost.
- **Deploy:** two k3s clusters, one per region, joined by a WireGuard link that carries the remote
  transport. In Docker, `deploy/docker/regions.yml` runs two regions of two nodes each at the small profile.
- **Proof:** `scripts/chaos-regions.sh` puts load on both regions, with half the transfers crossing, then cuts
  the link between the regions for 60 s and restores it. Both regions go on serving their own accounts; the
  crossing transfers wait and then settle; and the global ledger is conserved in every currency.

## Why this shape

Homing each account keeps the single-writer rule that makes the ledger simple. It is how real banks split
branches and entities, and it turns datacentre replication into ordinary sagas that already survive a partner
being away. The alternative, a globally consistent database under one journal, puts a WAN consensus round trip on
every write, and still needs one writer per account. A stretched Lark cluster would need a third site to break
ties.

## Depends on

Lark 0103 (entities in another cluster). Until it lands there is no workaround worth building: HTTP between the
regions would duplicate what the federation does.

## Stack

- [ ] **`regions-routing`** — region in account and transfer ids, `bank.region(s)`, routing to the other region.
      Done when: in one JVM, two regions open accounts, and each answers a balance for the other's account.
- [ ] **`regions-transfers`** — crossing sagas, `sentAbroad` and `receivedFromAbroad`, `GET /ledger/global`.
      Done when: a crossing transfer with the other region stopped stays `Debited`, and completes once it is back.
- [ ] **`regions-docker`** — `regions.yml`, `chaos-regions.sh`, and the numbers in the README.
      Done when: the chaos run above ends globally conserved.
- [ ] **`regions-k8s`** — two clusters' manifests and the cross-cluster link, documented; not run here.

## Acceptance

```bash
./gradlew build
scripts/chaos-regions.sh     # both regions serve through the cut; globally conserved after it
```

## Open questions

1. **Region in the id, or a directory of accounts to regions?** Recommended: in the id. It needs no lookup and
   cannot disagree with itself; moving an account between regions becomes closing it and opening another.
2. **Cross-region books as totals, or as clearing accounts (due to / due from)?** Recommended: totals now, and
   clearing accounts once the bank keeps its own books (the double-entry spec that follows).
3. **Region names:** `north` and `south`, or ISO-like codes? Recommended: short lowercase names, which read well
   in ids.
4. **Should a request for another region's account be forwarded, or refused with where to go?** Recommended:
   forwarded. A client that finds out later still works.
