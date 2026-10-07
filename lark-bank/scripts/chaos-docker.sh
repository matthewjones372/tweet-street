#!/usr/bin/env bash
# The bank in Docker under load while its parts fail one at a time, then the books at rest. Each fault lasts longer
# than downing's stableAfter (10 s here), so a node cut off or frozen is downed, ends its process, and Docker starts
# it again as a new life (lark spec 0097). The load goes to bank-1 throughout; bank-1 itself is rolled last, after it.
#   scripts/chaos-docker.sh          RATE and SECONDS_OF_LOAD override 200/s for 420 s, long enough for every fault
#   SCREENING_ENABLED=true scripts/chaos-docker.sh    each transfer screened too, and the check stopped for 20 s
#   LEAK=1 scripts/chaos-docker.sh   an ops token written to bank-1's log on purpose, which the end's search must find
# It ends by searching every line the run caused for a secret (bank spec 0023), and fails on any.
set -uo pipefail
cd "$(dirname "$0")/.."
rate="${RATE:-200}" seconds="${SECONDS_OF_LOAD:-420}"
compose=(docker compose -f deploy/docker/compose.yml)
net=lark-bank_default
log=build/chaos-docker.log
mkdir -p build
: > "$log"
say() { echo "chaos: $(date +%T) $*" | tee -a "$log"; }
# The cluster view is for ops (bank spec 0021): a token from compose's test issuer, asked for once it is up.
ops_token() {
  [ -n "${OPS_TOKEN:-}" ] || OPS_TOKEN=$(curl -sf -X POST localhost:9000/token \
    -d "grant_type=urn:lark-bank:test-token&subject=chaos&groups=ops" | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
  echo "$OPS_TOKEN"
}
view() { curl -s --max-time 3 -H "Authorization: Bearer $(ops_token)" localhost:8080/cluster | python3 -c '
import sys, json
try:
    v = json.load(sys.stdin)
    print(" ".join(m["node"].split("@")[0] + ":" + m["status"] for m in v["members"]), "unreachable", v["unreachable"])
except Exception as e:
    print("no answer", e)' 2>&1; }
# Each node's own view, asked inside its container: what a node dropped from bank-1's view thinks.
views() {
  for n in 1 2 3; do
    printf 'bank-%s sees: ' "$n"
    docker exec -e TOKEN="$(ops_token)" lark-bank-bank-$n-1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /cluster HTTP/1.0\r\nHost: localhost\r\nAuthorization: Bearer $TOKEN\r\n\r\n" >&3 && cat <&3' 2>/dev/null |
      tail -1 | python3 -c '
import sys, json
try:
    v = json.load(sys.stdin)
    print(" ".join(m["node"].split("@")[0] + ":" + m["status"] + ":" + str(m["upNumber"]) for m in v["members"]), "leader", v["leader"], "unreachable", v["unreachable"])
except Exception as e:
    print("no answer")'
  done
}
# Requests failed so far, from the load's last progress line: read at each fault's end, to see what each one cost.
failed() { grep '^proofload:' build/chaos-load.log 2>/dev/null | grep -oE '[0-9,]+ failed' | tail -1 | tr -d ,; }
restarts() { for n in 1 2 3; do printf "bank-%s:%s " "$n" "$(docker inspect -f '{{.RestartCount}}' lark-bank-bank-$n-1)"; done; echo; }
settled() { # three members Up and nobody unreachable, within $1 seconds
  local until=$((SECONDS + $1))
  while [ $SECONDS -lt $until ]; do
    v=$(view); if [ "$(grep -o ':Up' <<<"$v" | wc -l)" -eq 3 ] && grep -q 'unreachable \[\]' <<<"$v"; then say "settled: $v ($(failed) so far)"; return 0; fi
    sleep 2
  done
  say "NOT SETTLED after $1 s: $(view)"; views | tee -a "$log"; return 1
}

./gradlew -q :app:installDist :loadtest:installDist
docker build -q -t lark-bank:dev -f deploy/docker/bank.Dockerfile . >/dev/null
docker build -q -t lark-bank-loadtest:dev -f deploy/docker/loadtest.Dockerfile . >/dev/null
"${compose[@]}" down -v >/dev/null 2>&1
"${compose[@]}" up -d --wait >/dev/null 2>&1 || { say "compose did not come up"; exit 1; }
settled 60 || exit 1

say "load: transfers at $rate/s for $seconds s"
"${compose[@]}" run --rm -e SCENARIO=transfers -e RATE="$rate" -e SECONDS="$seconds" load > build/chaos-load.log 2>&1 &
load=$!
sleep 30

say "1. bank-3 cut off from the network for 25 s"
docker network disconnect "$net" lark-bank-bank-3-1; sleep 25; docker network connect "$net" lark-bank-bank-3-1
say "   reconnected: $(view)"; settled 120

say "2. bank-2 frozen for 20 s, as a long GC pause would"
docker pause lark-bank-bank-2-1 >/dev/null; sleep 20; docker unpause lark-bank-bank-2-1 >/dev/null
say "   thawed: $(view)"; settled 120

say "3. journal database db-1 (postgres-2) stopped for 20 s"
docker stop -t 1 lark-bank-postgres-2-1 >/dev/null; sleep 20; docker start lark-bank-postgres-2-1 >/dev/null
say "   started again: $(view)"; settled 120

say "4. Kafka frozen for 30 s"
docker pause lark-bank-kafka-1 >/dev/null; sleep 30; docker unpause lark-bank-kafka-1 >/dev/null
say "   thawed: $(view)"; settled 60

if [ "${SCREENING_ENABLED:-false}" = true ]; then
  say "4b. the screening check stopped for 20 s"
  docker stop -t 1 lark-bank-check-1 >/dev/null; sleep 20; docker start lark-bank-check-1 >/dev/null
  say "   started again: $(view)"; settled 60
fi

say "5. rolling restart of bank-3 then bank-2"
for n in 3 2; do docker restart -t 45 lark-bank-bank-$n-1 >/dev/null; settled 120; done

# 6. A pod nobody listed reads the bank's account events (bank spec 0021): with no user it is refused before it reads a
# byte, and as a service the topic does not name it is refused the topic. The checks reading what is theirs shows the
# probe would have read something, so each refusal is the fence and not an empty topic.
read_as() { # user password topic group: how many records it read in 15 s, and why it stopped
  # Never committing: the probe must not move a real group's place.
  local config="enable.auto.commit=false"
  [ -n "$1" ] && config="$config
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=\"$1\" password=\"$2\";"
  docker run --rm --network "$net" --entrypoint bash -e CONFIG="$config" apache/kafka:3.9.1 -c "
    printf '%s\n' \"\$CONFIG\" > /tmp/c.properties
    timeout 40 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --consumer.config /tmp/c.properties \
      --topic $3 --group $4 --from-beginning --max-messages 5 --timeout-ms 15000 2>&1" |
    grep -aoE 'Processed a total of [0-9]+|[A-Za-z]+(Authentication|Authorization)Exception' | sort -u | tr '\n' ' '
}
breached=0
say "6. a pod nobody listed reads bank.account-events"
theirs=$(read_as checks checks-kafka bank.account-events checks-monitoring-probe-$RANDOM)
say "   the checks, under a group not theirs: $theirs"
case "$theirs" in *GroupAuthorizationException*) ;; *) breached=1 ;; esac
anyone=$(read_as "" "" bank.account-events chaos-$RANDOM)
say "   with no user: $anyone"
case "$anyone" in *"total of 0"*) ;; *) breached=1 ;; esac
wrong=$(read_as approvals approvals-kafka bank.account-events chaos-$RANDOM)
say "   as Approvals, which the topic does not name: $wrong"
case "$wrong" in *AuthorizationException*) ;; *) breached=1 ;; esac
own=$(read_as checks checks-kafka bank.account-events checks-monitoring)
say "   and the checks, as themselves: $own"
case "$own" in *"total of "[1-9]*) ;; *) say "   the checks read nothing: the probe proves nothing"; breached=1 ;; esac
[ "$breached" = 0 ] && say "   refused, and the checks still read theirs" || say "   FENCE BREACHED"

wait "$load"; code=$?
grep -E '^requests|^  |LEDGER' build/chaos-load.log | tee -a "$log"
say "load exited $code; restarts: $(restarts)"
say "final view: $(view)"
if [ "${SCREENING_ENABLED:-false}" = true ]; then
  # Per node life: a node restarted during the run counts from its restart.
  for n in 1 2 3; do
    say "bank-$n screened unanswered: $(docker exec lark-bank-bank-$n-1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /metrics HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3' 2>/dev/null | grep '^bank_transfer_screened_unanswered' | awk '{print $2}')"
  done
  say "check: $(docker logs lark-bank-check-1 2>&1 | grep 'asked' | tail -1)"
fi
for n in 1 2 3; do say "bank-$n errors logged: $(docker logs lark-bank-bank-$n-1 2>&1 | grep -c '"level":"ERROR"')"; done
for n in 1 2 3; do docker logs -t lark-bank-bank-$n-1 > build/chaos-bank-$n.log 2>&1; done
for n in 1 2 3; do grep "cluster: " build/chaos-bank-$n.log | sed "s/^/bank-$n /"; done | sort -k2 > build/chaos-membership.log
say "membership changes: build/chaos-membership.log ($(wc -l < build/chaos-membership.log) lines)"
# No secret on any line (bank spec 0023): every container's log and the load's, searched for compose's secrets and for
# anything shaped like a credential. LEAK=1 first writes a real token through bank-1's own stdout, as a careless log
# line would, to show the search finds one.
if [ "${LEAK:-}" = 1 ]; then
  docker exec -e TOKEN="$(ops_token)" lark-bank-bank-1-1 bash -c \
    'echo "{\"level\":\"DEBUG\",\"service\":\"lark-bank\",\"msg\":\"calling with Authorization: Bearer $TOKEN\"}" > /proc/1/fd/1'
  say "leaked an ops token into bank-1's log on purpose"
fi
"${compose[@]}" logs --no-color > build/chaos-all.log 2>&1
"${compose[@]}" config --format json > build/chaos-compose.json
if scripts/log-secrets.py build/chaos-compose.json build/chaos-all.log build/chaos-load.log > build/chaos-secrets.log; then
  leaked=0; say "no secret on any of $(wc -l < build/chaos-all.log) lines"
else
  leaked=1; say "SECRETS IN THE LOGS: $(wc -l < build/chaos-secrets.log) found, in build/chaos-secrets.log"; head -5 build/chaos-secrets.log | tee -a "$log"
fi
[ "${KEEP:-}" = 1 ] || "${compose[@]}" down -v >/dev/null 2>&1
grep -q "LEDGER CONSERVED" build/chaos-load.log && [ "$breached" = 0 ] && [ "$leaked" = 0 ]
