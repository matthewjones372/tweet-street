#!/usr/bin/env bash
# For the length of a run: every 45 s deletes the primary of one journal database, the two in turn, and says how long
# the operator took to promote the replica and to bring the old primary back as the new replica (bank spec 0008).
# While a database has one instance, its writes wait: dataDurability is `required`.
#   scripts/load.sh transfers & scripts/chaos-db.sh 120
set -euo pipefail
seconds="${1:-120}"
end=$((SECONDS + seconds))
round=0

primary() { kubectl -n lark-bank get cluster "$1" -o jsonpath='{.status.currentPrimary}'; }

while [ $SECONDS -lt $end ]; do
  sleep 45
  db="bank-db-$((round % 2))"
  round=$((round + 1))
  old=$(primary "$db")
  echo "chaos-db: deleting $db's primary, $old"
  kubectl -n lark-bank delete pod "$old" --wait=false
  started=$SECONDS
  until now=$(primary "$db") && [ -n "$now" ] && [ "$now" != "$old" ]; do
    if [ $((SECONDS - started)) -gt 180 ]; then echo "chaos-db: $db has no new primary after 180 s"; break; fi
    sleep 1
  done
  echo "chaos-db: $db promoted $(primary "$db") after $((SECONDS - started)) s"
  kubectl -n lark-bank wait "cluster/$db" --for=condition=Ready --timeout=5m >/dev/null &&
    echo "chaos-db: $db has two instances again after $((SECONDS - started)) s"
done
