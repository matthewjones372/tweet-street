#!/usr/bin/env bash
# The model applied to OpenFGA (lark-bank spec 0022's access-server): the store found by name, or made, and the model
# in this image written to it as its newest version, which every client reads. Run by the access-model Job each time
# the image changes, so the model in the cluster is the model on main. FGA_API_URL and FGA_API_TOKEN say where, and as
# whom; MODEL is the model's file.
set -euo pipefail
: "${FGA_API_URL:?}" "${FGA_API_TOKEN:?}" "${MODEL:?}"
store=${STORE_NAME:-bank}

until curl -fsS "$FGA_API_URL/healthz" >/dev/null 2>&1; do echo "access: waiting for $FGA_API_URL"; sleep 2; done

id=$(fga store list --max-pages 0 | jq -r --arg name "$store" '[.stores[] | select(.name == $name) | .id][0] // empty')
if [ -z "$id" ]; then
  id=$(fga store create --name "$store" | jq -r .store.id)
  echo "access: store $store made, $id"
fi
model=$(fga model write --store-id "$id" --file "$MODEL" | jq -r .authorization_model_id)
echo "access: model $model is store $store's newest ($id)"
