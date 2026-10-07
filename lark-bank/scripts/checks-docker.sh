#!/usr/bin/env bash
# The bank beside the real checks (bank spec 0018), in Docker: bank-checks built from ../bank-checks, signed in to as
# an admin through compose's test issuer (spec 0019), a screening rule and a monitoring rule made live through its admin
# API (as the wizard makes them), and then a transfer the first declines and a withdrawal the second flags. KEEP=1
# leaves it up, the wizard on :8090, signed in to as anyone in `risk`.
set -euo pipefail
cd "$(dirname "$0")/.."
checks="${CHECKS_SOURCE:-../bank-checks}"
say() { echo "checks-docker: $(date +%T) $*"; }
compose=(docker compose -f deploy/docker/compose.yml --profile checks)
json=(-H 'Content-Type: application/json')

say "building the bank and the checks"
./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null
(cd "$checks" && nix develop -c sbt -batch app/stage >/dev/null && docker build -q -t bank-checks:dev . >/dev/null)

"${compose[@]}" down -v >/dev/null 2>&1 || true
SCREENING_ENABLED=true SCREENING_URL=http://checks:8090 "${compose[@]}" up -d --wait >/dev/null
say "up; the wizard is on http://localhost:8090"

# Signed in as a browser would: to the issuer, through its form as Ada in risk, and back to the check's callback.
jar=$(mktemp)
authorize=$(curl -sf -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' localhost:8090/login)
callback=$(curl -sf -o /dev/null -w '%{redirect_url}' -X POST "${authorize%%/authorize*}/approve" \
  --data "${authorize#*\?}&subject=ada&groups=risk")
curl -sf -c "$jar" -b "$jar" -o /dev/null "$callback"
say "signed in to the check as $(curl -sf -b "$jar" localhost:8090/me)"
rule() { # name, record, verdict JSON
  curl -sf -b "$jar" "${json[@]}" -X POST "localhost:8090/rules/$1/versions" \
    -d "{\"record\":\"$2\",\"document\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$3"),\"severity\":\"high\",\"position\":1,\"status\":\"live\"}"
}
say "live: $(rule large-transfer transfer '{"op":"gte","path":"amount","value":500}')"
say "live: $(rule large-withdrawal movement '{"op":"and","left":{"op":"eq","path":"kind","value":"withdrawal"},"right":{"op":"gte","path":"amount","value":300}}')"
sleep 2

# Tokens from compose's test issuer (bank spec 0021): Ada holds ada, Bob holds bob.
token() { curl -sf -X POST localhost:9000/token -d "grant_type=urn:lark-bank:test-token&subject=$1" | sed 's/.*"access_token":"\([^"]*\)".*/\1/'; }
ada_token=$(token ada)
bob_token=$(token bob)
as() { local who=$1; shift; curl -sf "${json[@]}" -H "Authorization: Bearer $who" -X "$1" "localhost:8080$2" ${3:+-d "$3"}; }
bank() { as "$ada_token" "$@"; }
bank PUT /accounts/ada '{"currency":"GBP","initial":"2000.00"}' >/dev/null
as "$bob_token" PUT /accounts/bob '{"currency":"GBP","initial":"0"}' >/dev/null
small=$(bank PUT /transfers/small-1 '{"from":"ada","to":"bob","amount":{"value":"100.00","currency":"GBP"}}')
large=$(bank PUT /transfers/large-1 '{"from":"ada","to":"bob","amount":{"value":"900.00","currency":"GBP"}}')
say "a small transfer: $small"
say "a large transfer: $large"
bank POST /accounts/ada/withdrawals '{"amount":{"value":"350.00","currency":"GBP"},"reference":"atm-1"}' >/dev/null

flagged=""
for _ in $(seq 60); do
  flagged=$(curl -sf -b "$jar" "localhost:8090/flags?limit=10")
  grep -q large-withdrawal <<<"$flagged" && break
  sleep 1
done
say "flags: $flagged"
say "ada's balance: $(bank GET /accounts/ada)"

ok=true
grep -q '"status":"Completed"' <<<"$small" || ok=false
grep -q '"status":"Rejected"' <<<"$large" && grep -q 'Declined by large-transfer, version 1' <<<"$large" || ok=false
grep -q '"rule":"large-withdrawal"' <<<"$flagged" || ok=false
[ "${KEEP:-}" = 1 ] || "${compose[@]}" down -v >/dev/null 2>&1
$ok && say "the rule declined the large transfer, and the withdrawal was flagged" || { say "FAILED"; exit 1; }
