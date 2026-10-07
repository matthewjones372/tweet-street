#!/usr/bin/env bash
# A kind cluster with the bank on three workers, its journal on two CloudNativePG clusters, Kafka, Prometheus and
# Grafana; the checks (spec 0018), Approvals (spec 0019) and bank-access (spec 0022) beside it, built from
# ../bank-checks, ../bank-approvals and ../bank-access; and Estate (spec 0026), its catalog checked first.
set -euo pipefail
cd "$(dirname "$0")/.."
checks="${CHECKS_SOURCE:-../bank-checks}"
approvals="${APPROVALS_SOURCE:-../bank-approvals}"
access="${ACCESS_SOURCE:-../bank-access}"

./gradlew :app:installDist :loadtest:installDist
docker build -f deploy/docker/bank.Dockerfile -t lark-bank:dev .
docker build -f deploy/docker/loadtest.Dockerfile -t lark-bank-loadtest:dev .
(cd "$checks" && nix develop -c sbt -batch app/stage && docker build -t bank-checks:dev .)
(cd "$approvals" && ./gradlew :app:installDist && docker build -t bank-approvals:dev .)
(cd "$access" && docker load < "$(nix build .#model-image --print-out-paths)" && nix develop -c ./gradlew :sync:installDist &&
  docker build -t bank-access-sync:dev .)

scripts/estate-check.sh

kind get clusters | grep -qx lark-bank || kind create cluster --config "${KIND_CONFIG:-deploy/kind/cluster.yaml}"
kind load docker-image lark-bank:dev lark-bank-loadtest:dev bank-checks:dev bank-approvals:dev bank-access-model:dev bank-access-sync:dev --name lark-bank

scripts/operators.sh

kubectl apply -k deploy/k8s
kubectl -n lark-bank wait cluster/bank-db-0 cluster/bank-db-1 --for=condition=Ready --timeout=10m
kubectl -n lark-bank rollout status statefulset/kafka --timeout=5m
# A restarted image is not a changed spec: roll the bank so each pod runs what was just loaded.
kubectl -n lark-bank rollout restart statefulset/lark-bank
kubectl -n lark-bank rollout status statefulset/lark-bank --timeout=10m
kubectl -n lark-bank rollout restart deployment/checks deployment/bank-approvals
kubectl -n lark-bank rollout status deployment/checks --timeout=5m
kubectl -n lark-bank rollout status deployment/bank-approvals --timeout=5m

echo
echo "bank     http://localhost:8080/api-docs"
echo "sign in  http://localhost:8080/ (the test issuer, at localhost:9000: anyone may be anyone)"
echo "grafana  http://localhost:3000"
echo "checks   http://localhost:8090/ (signed in to through the test issuer, as anyone in risk)"
echo "estate   kubectl -n lark-bank port-forward svc/estate 8060:80, then http://localhost:8060/ (bank spec 0026)"
echo "approvals http://localhost:8070/ (scripts/asks.sh runs spec 0019's three cases against all of it)"
curl -s localhost:8080/ready; echo
