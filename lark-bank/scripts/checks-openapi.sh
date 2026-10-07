#!/usr/bin/env bash
# The check's OpenAPI document, as bank-checks commits it, copied here for ScreeningContractSpec (bank spec 0018).
set -euo pipefail
cd "$(dirname "$0")/.."
cp "${CHECKS_SOURCE:-../bank-checks}/openapi.json" api/src/test/resources/checks-openapi.json
