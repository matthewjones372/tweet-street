#!/usr/bin/env bash
# The bank in Docker, watched on its ops page, while transfers run and one node is killed and started again
# (bank spec 0007). Open http://localhost:8080/ops before the load starts; bank-1 serves it throughout.
#   scripts/demo.sh            RATE and SECONDS_OF_LOAD override 300/s for 120 s
set -euo pipefail
cd "$(dirname "$0")/.."
rate="${RATE:-300}" seconds="${SECONDS_OF_LOAD:-120}"
compose=(docker compose -f deploy/docker/compose.yml)

./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null
"${compose[@]}" up -d --wait
echo "demo: the ops page is http://localhost:8080/ops, and the customer pages http://localhost:8080/"
sleep "${PAUSE_BEFORE_LOAD:-10}"

mkdir -p build
echo "demo: transfers at $rate/s for $seconds s"
"${compose[@]}" run --rm -e SCENARIO=transfers -e RATE="$rate" -e SECONDS="$seconds" load > build/demo-load.log 2>&1 &
load=$!
sleep $((seconds / 3))
echo "demo: killing bank-3"
docker kill lark-bank-bank-3-1 >/dev/null
sleep $((seconds / 3))
# Back at the same address while the others still hold its earlier life: a new life of bank-3 (lark spec 0097).
echo "demo: starting bank-3 again"
docker start lark-bank-bank-3-1 >/dev/null
wait "$load" || true
grep -E '^requests|LEDGER' build/demo-load.log
