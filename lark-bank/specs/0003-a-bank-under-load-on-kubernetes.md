# 0003 — A bank under load on Kubernetes

## Problem

A cluster that has only run in one JVM has not been shown to work. The bank should run as several pods on a
local Kubernetes, and be driven hard enough to show where Lark's pieces hold and where they stop.

## Not doing

- No cloud deployment and no Helm chart: kind, kustomize, and one script.
- No TLS between nodes by default. `lark-actor-remote`'s `Tls.mutual` can be turned on in config.

## Shape

- **kind**: a three-worker cluster, with Postgres, Kafka (KRaft), Prometheus and Grafana in it, and the bank as
  a `StatefulSet` of three replicas behind a Service.
- **Discovery and downing**: `lark-cluster-kubernetes`, seeds from the pods API by label, and a `Lease` breaking
  an even split. Pods advertise `POD_IP`, and RBAC grants `pods: list` and `leases: get, create, update`.
- **Stopping**: SIGTERM releases the app graph, the producer drains, the node leaves, and its shards move before
  the pod goes. A node downed by the others exits, so Kubernetes restarts it.
- **Health**: readiness is the node being `Up` in its own view, and liveness is the graph having started.
- **Load**: Proofload as a Kubernetes `Job`, calling through Pelican's typed client, one scenario per profile.

| Scenario | What it shows |
|---|---|
| `spread` | 100k accounts, deposits and withdrawals: sharding, and journal write throughput |
| `hot` | thousands a second at one merchant account: the single-writer ceiling |
| `transfers` | cross-node sagas, and the ledger conserved throughout |
| `chaos` | pods deleted and scaled mid-load: nothing lost or doubled, and read models catching up |

- **Grafana**: requests, p99, journal appends, entities per node, projection lag and saga states, from
  `lark-micrometer` and the bank's own meters.

```bash
./deploy/kind/up.sh && ./deploy/kind/load.sh transfers
```

## Why this shape

A `StatefulSet` gives stable pod names, which makes the logs and the cluster view readable. A `Deployment` works
just as well, because the node's identity is its pod IP and its per-life uid. Proofload inside the cluster
measures the bank rather than a laptop's port-forward.

## Depends on

- **The single-writer ceiling in `hot`.** `persistent` appended once per command, so one account was bound by
  journal latency. `hot` showed it, and Lark spec 0086 ([lark#226](https://github.com/matthewjones372/lark/pull/226))
  batches the commands waiting into one append; the bank's accounts use `batch = 64`.
- **Lark#231, the side that stays never downs itself.** Until it merges, the bank builds against a snapshot that
  carries it.

## Stack

- [ ] **`image-and-manifests`** — Jib image, kustomize base, RBAC, `up.sh`.
      Done when: three pods show three `Up` members at `GET /cluster`.
- [ ] **`load`** — the loadtest image, the four profiles, `load.sh`.
      Done when: each profile writes a Proofload HTML report and `GET /ledger` says conserved afterwards.
- [ ] **`dashboards`** — Prometheus scrape and the Grafana board.
      Done when: the board shows all six panels during `spread`.

## Acceptance

```bash
./deploy/kind/up.sh && ./deploy/kind/load.sh chaos && curl -s localhost:8080/ledger
```

## Open questions

1. **Use a `StatefulSet` or a `Deployment`?** Recommend a `StatefulSet` for the readable names.
2. **Should Proofload report back?** Recommend an HTML report copied out of the Job's pod by `load.sh`.
