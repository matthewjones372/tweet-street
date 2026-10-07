#!/usr/bin/env bash
# Runs one scenario as a Job in the cluster and copies its Proofload report out.
#   scripts/load.sh spread|hot|transfers|chaos|payroll   (RATE, SECONDS, ACCOUNTS, HOT_ACCOUNTS override)
set -euo pipefail
cd "$(dirname "$0")/.."
scenario="${1:?spread, hot, transfers, chaos or payroll}"
rate="${RATE:-1000}" seconds="${SECONDS_OF_LOAD:-120}" accounts="${ACCOUNTS:-100000}" hot="${HOT_ACCOUNTS:-1}"

kubectl -n lark-bank delete job "load-$scenario" --ignore-not-found
sed -e "s/SCENARIO/$scenario/g" -e "s/RATE_VALUE/$rate/" -e "s/SECONDS_VALUE/$seconds/" \
    -e "s/ACCOUNTS_VALUE/$accounts/" -e "s/HOT_VALUE/$hot/" deploy/k8s/load-job.yaml | kubectl apply -f -

if [ "$scenario" = chaos ]; then scripts/chaos.sh "$seconds" & fi

pod=""
until [ -n "$pod" ]; do pod=$(kubectl -n lark-bank get pods -l "scenario=$scenario" -o name | head -1); sleep 1; done
kubectl -n lark-bank wait --for=condition=Ready "$pod" --timeout=2m
kubectl -n lark-bank logs -f "$pod" | tee "build/load-$scenario.log" &
until grep -qE "LEDGER (CONSERVED|DID NOT BALANCE)" "build/load-$scenario.log" 2>/dev/null; do sleep 2; done
mkdir -p build/reports
kubectl -n lark-bank cp "${pod#pod/}:/reports/$scenario.html" "build/reports/$scenario.html"
echo "report: build/reports/$scenario.html"
grep -q "LEDGER CONSERVED" "build/load-$scenario.log"
