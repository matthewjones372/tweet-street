#!/bin/sh
# Garage's one-time setup (bank spec 0010), idempotent: give the node its place in the layout, import the backups'
# key, and make the bucket it may read and write. Run by the garage-setup Job through Garage's admin API.
set -eu
api="${GARAGE_ADMIN:-http://garage:3903}"
auth="Authorization: Bearer $GARAGE_ADMIN_TOKEN"
call() { curl -fsS -H "$auth" -H "Content-Type: application/json" "$@"; }
field() { sed -n "s/.*\"$1\": *\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

until call "$api/v1/health" >/dev/null 2>&1; do echo "waiting for garage"; sleep 2; done

status=$(call "$api/v1/status")
node=$(echo "$status" | field node)
version=$(call "$api/v1/layout" | field version)
if [ "$version" = 0 ]; then
  echo "layout: $node, one zone, 1 TB"
  call -X POST "$api/v1/layout" -d "[{\"id\":\"$node\",\"zone\":\"home\",\"capacity\":1000000000000,\"tags\":[]}]" >/dev/null
  call -X POST "$api/v1/layout/apply" -d '{"version":1}' >/dev/null
fi

# A key imported once, and a bucket it may read and write.
store() { # key-id secret key-name bucket
  if ! call "$api/v1/key?id=$1" >/dev/null 2>&1; then
    echo "key: importing $1"
    call -X POST "$api/v1/key/import" -d "{\"accessKeyId\":\"$1\",\"secretAccessKey\":\"$2\",\"name\":\"$3\"}" >/dev/null
  fi
  bucket=$(call "$api/v1/bucket?globalAlias=$4" 2>/dev/null | field id || true)
  if [ -z "$bucket" ]; then
    echo "bucket: $4"
    bucket=$(call -X POST "$api/v1/bucket" -d "{\"globalAlias\":\"$4\"}" | field id)
  fi
  call -X POST "$api/v1/bucket/allow" \
    -d "{\"bucketId\":\"$bucket\",\"accessKeyId\":\"$1\",\"permissions\":{\"read\":true,\"write\":true,\"owner\":true}}" >/dev/null
  echo "garage: bucket $4 for $1"
}

store "$ACCESS_KEY_ID" "$ACCESS_SECRET_KEY" bank-backups bank-backups
# Traces (bank spec 0024), under a key of their own: Tempo cannot read the backups, nor they the traces.
if [ -n "${TRACES_KEY_ID:-}" ]; then store "$TRACES_KEY_ID" "$TRACES_SECRET_KEY" traces traces; fi
# Logs (bank spec 0023), likewise under a key of their own.
if [ -n "${LOGS_KEY_ID:-}" ]; then store "$LOGS_KEY_ID" "$LOGS_SECRET_KEY" logs logs; fi
echo "garage: ready"
