#!/usr/bin/env bash
# Lark-bank spec 0022's access-server in Docker: OpenFGA on Postgres with preshared keys, as the cluster runs it,
# the model applied by the image the access-model Job runs, then a relationship written and asked about. Needs the
# image loaded first: `docker load < $(nix build .#model-image --print-out-paths)`. KEEP=1 leaves it up on :8080.
set -euo pipefail
cd "$(dirname "$0")/.."
say() { echo "server-docker: $(date +%T) $*"; }
fail() { say "FAILED: $*"; exit 1; }
net=bank-access-test version=${OPENFGA_VERSION:-v1.21.0}
key=model-key-dev reader=bank-key-dev
down() { [ "${KEEP:-}" = 1 ] || { docker rm -f access-db access >/dev/null 2>&1; docker network rm "$net" >/dev/null 2>&1; } || true; }
trap down EXIT
down; KEEP= true
docker network create "$net" >/dev/null

docker run -d --name access-db --network "$net" -e POSTGRES_DB=access -e POSTGRES_USER=access -e POSTGRES_PASSWORD=access \
  public.ecr.aws/docker/library/postgres:17 >/dev/null
until docker exec access-db pg_isready -U access >/dev/null 2>&1; do sleep 1; done
db="postgres://access:access@access-db:5432/access?sslmode=disable"
docker run --rm --network "$net" "openfga/openfga:$version" migrate --datastore-engine postgres --datastore-uri "$db" >/dev/null
docker run -d --name access --network "$net" -p 8080:8080 \
  -e OPENFGA_DATASTORE_ENGINE=postgres -e OPENFGA_DATASTORE_URI="$db" \
  -e OPENFGA_AUTHN_METHOD=preshared -e OPENFGA_AUTHN_PRESHARED_KEYS="$key,$reader" \
  -e OPENFGA_CHECK_QUERY_CACHE_ENABLED=true -e OPENFGA_CHECK_QUERY_CACHE_TTL=10s \
  -e OPENFGA_PLAYGROUND_ENABLED=false -e OPENFGA_LOG_FORMAT=json \
  "openfga/openfga:$version" run >/dev/null
say "OpenFGA $version up"

# The model, as the Job applies it; twice, as a second deploy would.
for _ in 1 2; do
  docker run --rm --network "$net" -e FGA_API_URL=http://access:8080 -e FGA_API_TOKEN="$key" bank-access-model:dev
done

fga() { command fga --api-url http://localhost:8080 --api-token "$reader" "$@"; }
store=$(fga store list | jq -r '[.stores[] | select(.name == "bank") | .id] | if length == 1 then .[0] else error("stores named bank: \(length)") end')
models=$(fga model list --store-id "$store" | jq '.authorization_models | length')
say "store bank is $store, with $models versions of the model"

fga tuple write --store-id "$store" person:ada owner account:acc-1 >/dev/null
fga tuple write --store-id "$store" person:bob supporter account:acc-1 --condition-name not_expired \
  --condition-context '{"expires":"2030-01-01T00:00:00Z"}' >/dev/null
asks() { fga query check --store-id "$store" "$1" "$2" "$3" --context "{\"current_time\":\"$4\"}" | jq -r .allowed; }
[ "$(asks person:ada viewer account:acc-1 2026-10-02T12:00:00Z)" = true ] || fail "Ada does not see her account"
[ "$(asks person:ada payer account:acc-1 2026-10-02T12:00:00Z)" = true ] || fail "Ada cannot pay from her account"
[ "$(asks person:eve viewer account:acc-1 2026-10-02T12:00:00Z)" = false ] || fail "Eve sees Ada's account"
[ "$(asks person:bob viewer account:acc-1 2026-10-02T12:00:00Z)" = true ] || fail "Bob's grant does not let him see acc-1"
[ "$(asks person:bob payer account:acc-1 2026-10-02T12:00:00Z)" = false ] || fail "Bob's grant lets him pay"
[ "$(asks person:bob viewer account:acc-1 2030-01-01T00:00:01Z)" = false ] || fail "Bob's grant outlived its expiry"
say "Ada sees and pays acc-1, Eve does not see it, Bob sees it until his grant ends and never pays"

# Without a key, and with a wrong one, nothing is answered.
for token in "" wrong-key; do
  status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "http://localhost:8080/stores/$store/check" \
    -d '{"tuple_key":{"user":"person:ada","relation":"viewer","object":"account:acc-1"}}')
  [ "$status" = 401 ] || fail "a check with key '${token:-none}' answered $status"
done
say "a check with no key, or a wrong one, is refused (401)"
