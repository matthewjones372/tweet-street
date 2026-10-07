#!/usr/bin/env bash
# Installs lark's main into ~/.m2 as the snapshot gradle.properties names. LARK_DIR is a lark checkout;
# without one, it clones lark, which is private, so git needs credentials for it.
set -euo pipefail
lark="${LARK_DIR:-../lark}"
[ -d "$lark" ] || git clone https://github.com/matthewjones372/lark "$lark"
(cd "$lark" && ./gradlew publishToMavenLocal -x test -x check)
