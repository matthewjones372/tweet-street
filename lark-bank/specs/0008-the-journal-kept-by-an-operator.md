# 0008 — The journal kept by an operator

## Problem

On Kubernetes, each journal database (spec 0004) is one Postgres pod on one volume, a StatefulSet written by hand.
A bank that answers "done" once its journal has committed is then exactly as durable as one disk. If that pod's
node dies, every account and transfer whose slice lives there stops until a person brings it back, and the
commits since the last backup are gone, because there is no backup. The chaos scenario (spec 0003) kills bank pods
and never the database, so none of this has been tested.

## Not doing

- **No change to Docker Compose.** It stays plain Postgres, for a quick local run.
- **No backups to object storage.** Barman needs S3 or something like it, such as MinIO, which is a demo of its
  own. It is named in the open questions.
- **No connection pooler.** Each node already pools with Hikari, and three nodes are few connections.
- **No change to the bank's code.** Only its manifests and scripts change. See Depends on.

## Shape

- **The operator**, CloudNativePG, installed by `scripts/up.sh` from its release manifest, pinned to one version.
- **Two `Cluster` resources**, `bank-db-0` and `bank-db-1`, one per journal database. Each has a primary and a
  replica on different workers, and a commit waits for the replica before it answers:

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: bank-db-0, namespace: lark-bank }
spec:
  instances: 2                              # a primary and a replica; the operator spreads them across workers
  postgresql:
    synchronous: { method: any, number: 1 } # a commit is on two nodes before the bank hears it
    parameters: { max_connections: "400", shared_buffers: "512MB" }
  bootstrap:
    initdb: { database: bank, owner: bank, secret: { name: bank-db-credentials } }  # one user for both
  storage: { size: 5Gi }
```

- **The bank connects to each cluster's primary** through the operator's `-rw` Service:
  `DATABASE_URL=jdbc:postgresql://bank-db-0-rw:5432/bank` and `JOURNAL_DATABASES=db-1=…bank-db-1-rw…`. The user
  and password come from `bank-db-credentials`, which both clusters were bootstrapped with, so the bank keeps its
  one user and password.
- **Prometheus scrapes each instance's exporter**, and a Grafana row shows primary, replica lag and failovers
  for each database.
- **`scripts/chaos-db.sh`** deletes one cluster's primary (the pod labelled `cnpg.io/instanceRole=primary`) while
  `transfers` runs, and says when the operator has promoted the replica.

## Why this shape

A failover is what the bank's retry rules were written for. A commit that fails while the primary moves is
`Unavailable`: a 503 that says to try again with the same reference. The account's remembered references make
that retry harmless, and the saga and the sweeper carry a transfer on. What the bank cannot survive is losing a
commit it already answered, and synchronous replication is what rules that out. With it, a promoted replica holds
every commit the bank was told about. Without it, a failover can drop the last few, and a projection may already
have read them. The ledger check then fails, which is exactly what the chaos run should show. The alternative is a
StatefulSet with streaming replication and failover written by hand: that is Patroni, or this operator, rebuilt
badly. Recommended: the operator, with synchronous commits.

## Depends on

Nothing new from Lark or Pelican. Two behaviours are relied on and proven by the chaos run rather than assumed:

- **Lark's `JdbcJournal` gap timeout (0075)** reads past an `ordering` a promoted primary skipped. Postgres
  sequences hand out values in batches ahead of the commit, so a failover can leave a hole.
- **Hikari** drops connections to the old primary and opens new ones through the `-rw` Service, whose endpoint
  the operator moves to the new primary.

## Stack

- [ ] **`cnpg-clusters`**: the operator in `up.sh`, the two `Cluster` resources and the credentials secret, the
      bank on the `-rw` Services, and `postgres.yaml` gone.
      Done when: `scripts/up.sh` brings the bank up on kind, `scripts/load.sh transfers` conserves the ledger,
      and each database's replica reports streaming. *Built; not yet run on kind.*
- [ ] **`cnpg-chaos`**: `scripts/chaos-db.sh`, and the load run with it.
      Done when: `transfers` at 300/s with db-0's primary deleted mid-run ends conserved, with every failed request
      a 503 and none a 500, and the README records the pause the failover caused. *Built; not yet run on kind.*
- [ ] **`cnpg-watch`**: the exporter scraped, and the Grafana row.
      Done when: the dashboard shows the failover as a change of primary and a spike in replica lag. *Built; not
      yet run on kind.*

## Acceptance

```bash
scripts/up.sh
scripts/load.sh transfers & scripts/chaos-db.sh 120
curl -s localhost:8080/ledger     # conserved: true
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **Synchronous commits?** Yes, `method: any`, `number: 1`.
2. **Two instances each, or three?** Two. Three is the one number `instances` in `postgres.yaml`.
3. **Backups to MinIO?** Later, as a spec of its own.
4. **Which operator version?** 1.30.1, the newest release, pinned in `up.sh` as `CNPG_VERSION`. Its CRD spells
   the setting `synchronous`, with `method`, `number` and `dataDurability`.
5. **Can this be proven here?** No: kind does not run in this sandbox. The Stack entries are built and checked
   as far as can be done without a cluster, and each stays unticked until it has run on kind.

Settled while building:

- **`dataDurability: required`**, written out rather than left to the default, so that a database down to one
  instance blocks its writes rather than answer with one copy. With two instances, writes wait after a failover
  until the old primary has come back as the replica. `chaos-db.sh` times both the promotion and that return, and
  a `preferred` setting would trade the wait for a window of single-copy commits.
- **Checked without a cluster:** both `Cluster` resources validate against the v1.30.1 CRD's schema, with unknown
  fields made errors so a misspelling is caught. `kubectl kustomize deploy/k8s` renders them, the credentials
  secret and the bank's environment as intended, and `bash -n` passes both scripts.
