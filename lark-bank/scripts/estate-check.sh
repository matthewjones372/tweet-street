#!/usr/bin/env bash
# The bank's catalog checked by Estate's own check, from Estate's image (bank spec 0026): every mistake named, and a
# failure if there is one. scripts/up.sh runs it before applying anything.
set -euo pipefail
cd "$(dirname "$0")/.."
image="${ESTATE_IMAGE:-ghcr.io/matthewjones372/estate:main}"
docker run --rm -v "$PWD/deploy/k8s/estate:/catalog:ro" "$image" check /catalog/catalog.yaml
