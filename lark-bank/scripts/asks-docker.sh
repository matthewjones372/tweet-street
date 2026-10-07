#!/usr/bin/env bash
# scripts/asks.sh in Docker: the bank, the real checks and Approvals, from ../bank-checks and ../bank-approvals, in
# compose. KEEP=1 leaves it up: the wizard on :8090, Approvals on :8070.
set -euo pipefail
cd "$(dirname "$0")/.."
checks="${CHECKS_SOURCE:-../bank-checks}"
approvals="${APPROVALS_SOURCE:-../bank-approvals}"
say() { echo "asks-docker: $(date +%T) $*"; }
compose=(docker compose -f deploy/docker/compose.yml --profile checks --profile approvals)
down() { [ "${KEEP:-}" = 1 ] || "${compose[@]}" down -v >/dev/null 2>&1; }
trap down EXIT

say "building the bank, the checks and Approvals"
./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null
(cd "$checks" && nix develop -c sbt -batch app/stage >/dev/null && docker build -q -t bank-checks:dev . >/dev/null)
(cd "$approvals" && ./gradlew -q :app:installDist && docker build -q -t bank-approvals:dev . >/dev/null)

"${compose[@]}" down -v >/dev/null 2>&1 || true
SCREENING_ENABLED=true SCREENING_URL=http://checks:8090 APPROVALS_ENABLED=true ACCESS_ENABLED=true "${compose[@]}" up -d --wait >/dev/null
say "up: the wizard on http://localhost:8090, Approvals on http://localhost:8070"
scripts/asks.sh
