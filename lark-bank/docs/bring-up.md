# Bring-up

What is built but has not yet run anywhere real, in the order to run it: compose first, then kind.
Each step names the spec entry it proves; when it passes, tick that entry in its spec. Every command is from the
repository's root.

```mermaid
flowchart LR
    compose[1. Compose] --> kind[2. kind, scripts/up.sh]
```

## 1. Compose

- [ ] **Logs, end to end** (0023 `log-views`). Bring the bank up with the `logs` profile, run the load, then the
  runbook's [Following one transfer](runbook.md#following-one-transfer) queries against Loki on 3100. Look for one
  transfer's lines from three services, in order.

  ```bash
  docker compose -f deploy/docker/compose.yml --profile logs --profile checks up -d --wait
  docker compose -f deploy/docker/compose.yml run --rm -e SCENARIO=transfers -e RATE=100 -e SECONDS=60 load
  ```

## 2. kind, the quick path

`scripts/up.sh` builds every image, makes the cluster and applies `deploy/k8s`.

- [ ] **The bank on Kubernetes** (0003 `image-and-manifests`, 0008 `cnpg-clusters`). `GET /cluster` shows three `Up`
  members; `kubectl -n lark-bank get cluster` shows both databases healthy, each replica streaming.
- [ ] **The load** (0003 `load`). Each of `scripts/load.sh spread|hot|transfers|payroll` writes its HTML report under
  `build/`, and `GET /ledger` says conserved after each.
- [ ] **The board** (0003 `dashboards`, 0008 `cnpg-watch`). Grafana on 3000 shows every panel during `spread`.
- [ ] **A database failover** (0008 `cnpg-chaos`). `RATE=300 scripts/load.sh transfers` with `scripts/chaos-db.sh`
  beside it: conserved at the end, every failure a 503 and none a 500; the board shows the change of primary and
  the replica's lag. Write the pause it caused into the README.
- [ ] **Logs in Loki** (0023 `log-store`). `kubectl -n lark-bank port-forward svc/loki 3100`: a line a pod wrote is
  found within 10 s, and still found after `kubectl -n lark-bank delete pod loki-0` and its return.
- [ ] **Logs under chaos** (0023 `log-ship`). `scripts/chaos.sh 120` beside `scripts/load.sh transfers`: every
  service's lines are in Loki, the deleted pods' included, and Fluent Bit stays under 50 MiB (kind has no
  metrics-server: `docker exec lark-bank-worker crictl stats --label app=fluent-bit` on each worker).
- [ ] **Estate** (0026 `estate-deploy`). `kubectl -n lark-bank port-forward svc/estate 8060:80`, then
  `http://localhost:8060`: signed in through the test issuer as someone in `ops`, the four services; in no group,
  the no-access page.
- [ ] **Who can see what** (0022 `access-audit`). On `access.html`, signed in as someone in `auditor`: an account
  opened by the load shows its owner, with its opening event, and every member of `auditor`.
- [ ] **A silence** (0026 `alertmanager`). An alert silenced on Estate's page is in Alertmanager with its reason
  (`kubectl -n lark-bank port-forward svc/alertmanager 9093`).
- [ ] **Debug** (0026 `logging-configmaps`, `logging-approvals-sync`). Debug turned on for lark-bank, bank-approvals
  and access-sync from Estate: each one's DEBUG lines within two minutes, and none once Estate turns it off.

