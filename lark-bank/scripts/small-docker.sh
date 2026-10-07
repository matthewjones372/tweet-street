#!/usr/bin/env bash
# The bank held to the size of a small board (deploy/docker/small.yml, bank spec 0010): transfers at rising rates,
# each for a minute on a fresh cluster, until p99 passes a second or a request fails. What a node that size carries.
#   scripts/small-docker.sh          RATES overrides "25 50 100 150 200"
set -uo pipefail
cd "$(dirname "$0")/.."
compose=(docker compose -f deploy/docker/compose.yml -f deploy/docker/small.yml)
log=build/small-docker.log
mkdir -p build
: > "$log"

./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null

for rate in ${RATES:-25 50 100 150 200}; do
  "${compose[@]}" down -v >/dev/null 2>&1
  "${compose[@]}" up -d --wait >/dev/null 2>&1 || { echo "compose did not come up" | tee -a "$log"; exit 1; }
  "${compose[@]}" run --rm -e SCENARIO=transfers -e RATE="$rate" -e SECONDS=60 load > build/small-load.log 2>&1
  summary=$(grep -E '^requests' build/small-load.log)
  latency=$(grep -E '^  transfer' build/small-load.log | sed 's/^ *//')
  memory=$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | grep bank- | awk '{print $2}' | paste -sd' ')
  echo "$rate/s: $summary; $latency; memory $memory" | tee -a "$log"
  grep -q "failed 0" <<<"$summary" || break
  p99=$(grep -oE 'p99 [0-9.]+(ms|s)' <<<"$latency")
  [[ "$p99" =~ ms$ ]] || break
done
"${compose[@]}" down -v >/dev/null 2>&1
