# Specs

A spec says what should be true when a piece of the bank is done, before any code does. It is the brief the
implementation gets and what a reviewer checks the diff against. The layout and lifecycle are Lark's and Pelican's:

1. **Draft.** One page, from [TEMPLATE.md](TEMPLATE.md), numbered `NNNN-kebab-name.md`.
2. **Edit.** A human cuts it and answers its open questions.
3. **Commit.** The spec lands on its own; that commit is the go-ahead.
4. **Build.** One pull request per **Stack** entry, bottom-up.
5. **Close.** Tick each entry as its pull request merges.

This repository also exists to find what Lark and Pelican are missing. A gap found while building the bank is
written up as a spec in *that* repository, not worked around here in silence; a spec here names it under
**Depends on**, with the workaround the bank uses until it lands.

Specs 0010, 0017, 0020 and 0025 described a private home deployment (the machines, CI and Flux in that cluster, a
private Maven repository, Headlamp on its k3s) and are not published here. Other specs still mention them, and the
home overlay, by name.

| Spec | What |
|---|---|
| [0001](0001-money-that-moves-once.md) | accounts and transfers as sharded, event-sourced entities |
| [0002](0002-what-the-journal-adds-up-to.md) | read models, the conservation check, and Kafka |
| [0003](0003-a-bank-under-load-on-kubernetes.md) | the cluster on kind, and the load that proves it |
| [0004](0004-a-journal-on-two-databases.md) | the journal split by account across two Postgres databases |
| [0005](0005-the-wire-from-data-classes.md) | the wire from data classes, mapped by kimney |
| [0006](0006-a-bank-a-customer-can-use.md) | pages a customer uses, served beside the API |
| [0007](0007-the-bank-watched-live.md) | a live ops page, every node's snapshot over one stream |
| [0008](0008-the-journal-kept-by-an-operator.md) | the journal databases run by CloudNativePG, with synchronous replicas and a failover chaos run; built, not yet run on kind |
| [0009](0009-a-bank-someone-can-run.md) | chaos in Docker and what it found, alerts, backups, a runbook |
| [0011](0011-money-in-currencies.md) | money in currencies, crypto included, from the bank's own registry |
| [0012](0012-two-regions-both-open.md) | two regions, both open: each account homed in one (draft) |
| [0013](0013-a-leg-applied-once.md) | a transfer's legs applied once, however busy the account |
| [0014](0014-read-models-across-the-cluster.md) | the read models in partitions, spread over the nodes, with writers safe to run at once |
| [0015](0015-every-event-published.md) | every event published to Kafka as Protobuf, through Apicurio, from a package of its own; built |
| [0016](0016-money-as-a-bigdecimal.md) | money as a BigDecimal in the domain and on the wire |
| [0018](0018-the-first-check-outside-the-bank.md) | checks outside the bank: screening each transfer over HTTP before it debits, monitoring the events after, rules written in a wizard; built, not yet run on kind |
| [0019](0019-changes-more-than-one-person-agrees-to.md) | approvals: a service of its own where named people agree to a change before it takes effect, with an audit that shows tampering; rule versions first, OIDC through Pocket ID; built, every entry; Approvals not yet deployed at home |
| [0021](0021-who-may-see-what.md) | who may see what: a verified caller on every endpoint, the rules in one file, support seeing one account or acting as a customer only through an approved grant, every staff look recorded, row-level security, and Kafka, Grafana and the network fenced; built, every entry; the NetworkPolicies and Grafana's sign-in not yet run on a cluster |
| [0022](0022-who-may-do-what-in-one-place.md) | one place that answers who may do what across the estate: OpenFGA as `bank-access`, relationships derived from events, approvals and Pocket ID groups, added in shadow beside each service's own rules; settled; built to `access-shadow` |
| [0023](0023-every-log-line-in-one-place.md) | every service's log as JSON, kept in Loki on Garage and read in Grafana; settled; built, every entry; `log-store`, `log-ship` and `log-views` not yet run on a cluster |
| [0024](0024-a-request-followed-through-the-estate.md) | one trace per request across every node, actor, call and Kafka hop, in Tempo on Garage, linked to the logs; on Lark 0122 and 0123 and Pelican 0064 (built); settled; built, every entry |
| [0026](0026-the-estate-on-one-page.md) | the bank adopting Estate, a generic estate dashboard in its own repository: its catalog, its deploy, Alertmanager, and debug through logging ConfigMaps; built; debug for all three services built, its DEBUG lines not yet seen on a cluster |
| [0027](0027-a-daily-withdrawal-limit.md) | a daily limit on what an account pays out, withdrawals and debits together, per UTC day; built |
