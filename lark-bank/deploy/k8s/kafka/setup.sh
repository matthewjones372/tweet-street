#!/bin/bash
# Kafka's users and what each may do (bank spec 0021), idempotent, run beside the broker over its listener on
# 127.0.0.1:9094, which only something in the broker's own pod can reach and which is the one place nobody signs in.
# Each service signs in with SCRAM-SHA-512 as itself; a client that is none of them is refused before it reads a byte,
# and a service is refused every topic and group not named here. Passwords come from the environment
# (KAFKA_PASSWORD_BANK, _CHECKS, _APPROVALS, _ACCESS_SYNC), from the kafka-users Secret.
set -euo pipefail
bin=/opt/kafka/bin
local=(--bootstrap-server 127.0.0.1:9094)

until "$bin/kafka-broker-api-versions.sh" "${local[@]}" >/dev/null 2>&1; do sleep 2; done

user() { # name password
  "$bin/kafka-configs.sh" "${local[@]}" --alter --entity-type users --entity-name "$1" \
    --add-config "SCRAM-SHA-512=[iterations=8192,password=$2]" >/dev/null
}
allow() { # principal, then kafka-acls' own arguments
  local who=$1; shift
  "$bin/kafka-acls.sh" "${local[@]}" --add --allow-principal "User:$who" "$@" >/dev/null
}

user bank "$KAFKA_PASSWORD_BANK"
user checks "$KAFKA_PASSWORD_CHECKS"
user approvals "$KAFKA_PASSWORD_APPROVALS"
# Where access-sync is not run (compose), it has no password, and no user.
[ -z "${KAFKA_PASSWORD_ACCESS_SYNC:-}" ] || user access-sync "$KAFKA_PASSWORD_ACCESS_SYNC"

# The bank writes its own topics (spec 0015, 0021), reads approvals for support's grants (spec 0021).
for topic in bank.account-events bank.transfer-events bank.access-events; do
  allow bank --operation Write --operation Create --operation Describe --topic "$topic"
done
allow bank --operation Read --operation Describe --topic bank.approval-events
allow bank --operation Read --group lark-bank-access
allow bank --operation IdempotentWrite --cluster

# The checks read accounts' events and approvals (specs 0018, 0019), and write their flags.
for topic in bank.account-events bank.approval-events; do
  allow checks --operation Read --operation Describe --topic "$topic"
done
allow checks --operation Read --group checks-monitoring
allow checks --operation Read --group checks-approvals
allow checks --operation Write --operation Create --operation Describe --topic checks.flags
allow checks --operation IdempotentWrite --cluster

# Approvals writes its requests' events (spec 0019), and nothing else.
allow approvals --operation Write --operation Create --operation Describe --topic bank.approval-events
allow approvals --operation IdempotentWrite --cluster

# Every service asking bank-access keeps its decisions on one topic, through access-client (spec 0022).
for service in bank checks approvals; do
  allow "$service" --operation Write --operation Create --operation Describe --topic bank.access-decisions
done

# access-sync reads accounts' openings and the approvals (spec 0022): the accounts in a group of its own, the
# approvals from the start each time, in no group.
allow access-sync --operation Read --operation Describe --topic bank.account-events
allow access-sync --operation Read --group access-sync
allow access-sync --operation Read --operation Describe --topic bank.approval-events

echo "kafka: users and ACLs in place"
