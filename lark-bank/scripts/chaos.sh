#!/usr/bin/env bash
# For the length of a run: deletes a bank pod every 30 s, scales to four and back, and says what it did.
set -euo pipefail
seconds="${1:-120}"
end=$((SECONDS + seconds))
round=0
while [ $SECONDS -lt $end ]; do
  sleep 30
  round=$((round + 1))
  case $((round % 3)) in
    1) victim=$(kubectl -n lark-bank get pods -l app=lark-bank -o name | shuf -n 1)
       echo "chaos: deleting $victim"; kubectl -n lark-bank delete "$victim" --wait=false ;;
    2) echo "chaos: scaling to 4"; kubectl -n lark-bank scale statefulset/lark-bank --replicas=4 ;;
    0) echo "chaos: scaling back to 3"; kubectl -n lark-bank scale statefulset/lark-bank --replicas=3 ;;
  esac
done
kubectl -n lark-bank scale statefulset/lark-bank --replicas=3
