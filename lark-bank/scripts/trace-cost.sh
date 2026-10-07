#!/usr/bin/env bash
# What tracing costs (bank spec 0024's `trace-cost`): the same load with tracing off, at 10% sampled and at 100%, each
# on a fresh cluster with Tempo up when tracing is on, and what each did: failures, latency, the nodes' memory at the
# end, and Tempo's.
#   scripts/trace-cost.sh       RATE and SECONDS_OF_LOAD override 400/s for 60 s; RUNS "off 0.1 1.0"
set -uo pipefail
cd "$(dirname "$0")/.."
rate="${RATE:-400}" seconds="${SECONDS_OF_LOAD:-60}"
log=build/trace-cost.log
mkdir -p build
: > "$log"

./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null

for sampled in ${RUNS:-off 0.1 1.0}; do
  if [ "$sampled" = off ]; then
    export TELEMETRY_ENABLED=false TELEMETRY_SAMPLED=0
    compose=(docker compose -f deploy/docker/compose.yml)
  else
    export TELEMETRY_ENABLED=true TELEMETRY_SAMPLED="$sampled"
    compose=(docker compose -f deploy/docker/compose.yml --profile traces)
  fi
  "${compose[@]}" down -v >/dev/null 2>&1
  "${compose[@]}" up -d --wait >/dev/null 2>&1 || { echo "compose did not come up" | tee -a "$log"; exit 1; }
  "${compose[@]}" run --rm -e SCENARIO=transfers -e RATE="$rate" -e SECONDS="$seconds" load > build/trace-cost-load.log 2>&1
  summary=$(grep -E '^requests' build/trace-cost-load.log)
  latency=$(grep -E '^  transfer' build/trace-cost-load.log | sed 's/^ *//')
  memory=$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | grep -E 'bank-[123]-1' | awk '{print $2}' | paste -sd' ')
  tempo=$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | grep tempo | awk '{print $2}')
  echo "tracing $sampled at $rate/s: $summary; $latency; nodes $memory${tempo:+; tempo $tempo}" | tee -a "$log"
done
docker compose -f deploy/docker/compose.yml --profile traces down -v >/dev/null 2>&1
