#!/usr/bin/env bash
# Spec 0019's checks-asks, against whatever answers on localhost: the bank on :8080, the test issuer on :9000, the
# checks' wizard on :8090 and Approvals on :8070, as compose (asks-docker.sh) and kind (up.sh) both publish them.
# Ada (risk) makes rules live in the check; each waits in Approvals until two others in risk approve, and only then
# declines. A rejected version never goes live, and a low rule switched off is approved automatically and applied at
# once. And bank spec 0021's staff: someone in support sees one customer's account only while a grant someone else
# approved in Approvals lives, and acts as a customer, read-only, only while another lives.
set -euo pipefail
say() { echo "asks: $(date +%T) $*"; }
fail() { say "FAILED: $*"; exit 1; }
json=(-H 'Content-Type: application/json')

# Ada signs in to the check as a browser would: to the issuer, through its form, and back to the check's callback.
jar=$(mktemp)
authorize=$(curl -sf -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' localhost:8090/login)
callback=$(curl -sf -o /dev/null -w '%{redirect_url}' -X POST "${authorize%%/authorize*}/approve" \
  --data "${authorize#*\?}&subject=ada&groups=risk")
curl -sf -c "$jar" -b "$jar" -o /dev/null "$callback"

field() { python3 -c 'import json,sys; v=json.load(sys.stdin); [v:=v[k] for k in sys.argv[1:]]; print(v if v is not None else "")' "$@"; }
version() { # name, record, severity, status, verdict JSON: what the wizard posts
  curl -sf -b "$jar" "${json[@]}" -X POST "localhost:8090/rules/$1/versions" \
    -d "{\"record\":\"$2\",\"document\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$5"),\"severity\":\"$3\",\"position\":1,\"status\":\"$4\"}"
}
approval() { curl -sf -b "$jar" "localhost:8090/rules/$1" | python3 -c "import json,sys; v=json.load(sys.stdin)['versions']; print(v[$2-1]['approval'])"; }
until_approval() { for _ in $(seq 60); do [ "$(approval "$1" "$2")" = "$3" ] && return 0; sleep 1; done; return 1; }

# Tokens from the issuer (bank spec 0021): customers for the bank, and people in risk for Approvals.
token() { curl -sf -X POST localhost:9000/token -d "grant_type=urn:lark-bank:test-token&subject=$1&groups=${2:-}&audience=${3:-lark-bank}" | field access_token; }
bank() { curl -sf "${json[@]}" -H "Authorization: Bearer $ada_bank" -X "$1" "localhost:8080$2" ${3:+-d "$3"}; }
ask() { local who=$1; shift; curl -s -o /dev/null -w '%{http_code}' "${json[@]}" -H "Authorization: Bearer $who" -X POST "localhost:8070$1" -d "$2"; }
request() { curl -sf -H "Authorization: Bearer $bob" "localhost:8070/requests/$1"; }
ada_bank=$(token ada); bob_bank=$(token bob)
ada=$(token ada risk bank-approvals); bob=$(token bob risk bank-approvals); cy=$(token cy risk bank-approvals)
bank PUT /accounts/ada '{"currency":"GBP","initial":"5000.00"}' >/dev/null
curl -sf "${json[@]}" -H "Authorization: Bearer $bob_bank" -X PUT localhost:8080/accounts/bob -d '{"currency":"GBP","initial":"0"}' >/dev/null
transfer() { bank PUT "/transfers/$1" "{\"from\":\"ada\",\"to\":\"bob\",\"amount\":{\"value\":\"$2\",\"currency\":\"GBP\"}}" | field status; }

# 1. Made live, it waits for two others in risk: until then the transfer it covers goes through.
first=$(version large-transfer transfer high live '{"op":"gte","path":"amount","value":500}')
r1=$(field request <<<"$first")
[ "$(field approval <<<"$first")" = waiting ] || fail "version 1 is not waiting: $first"
say "version 1 waits on request $r1"
[ "$(transfer t-1 900.00)" = Completed ] || fail "a version waiting for approval declined a transfer"
hash=$(request "$r1" | field hash)
[ "$(ask "$ada" "/requests/$r1/approve" "{\"hash\":\"$hash\"}")" = 409 ] || fail "Ada approved her own change"
[ "$(ask "$bob" "/requests/$r1/approve" "{\"hash\":\"$hash\"}")" = 200 ] || fail "Bob's approval"
[ "$(transfer t-2 900.00)" = Completed ] || fail "one approval of two made it live"
[ "$(ask "$cy" "/requests/$r1/approve" "{\"hash\":\"$hash\"}")" = 200 ] || fail "Cy's approval"
until_approval large-transfer 1 given || fail "version 1 was never made live"
for _ in $(seq 20); do [ "$(transfer "t-3-$RANDOM" 900.00)" = Rejected ] && declined=1 && break; sleep 0.5; done
[ -n "${declined:-}" ] || fail "the approved rule did not decline the next transfer"
for _ in $(seq 30); do [ "$(request "$r1" | field state)" = applied ] && break; sleep 1; done
[ "$(request "$r1" | field state)" = applied ] || fail "request $r1 is not applied"
say "approved by bob and cy, live, declining 900.00, and $r1 applied"

# 2. A rejected version never goes live: the one before it stays in force.
second=$(version large-transfer transfer high live '{"op":"gte","path":"amount","value":100}')
r2=$(field request <<<"$second")
[ "$(ask "$bob" "/requests/$r2/reject" '{"comment":"100 would stop the rent"}')" = 200 ] || fail "Bob's rejection"
until_approval large-transfer 2 refused || fail "version 2 was not refused"
[ "$(transfer t-4 300.00)" = Completed ] || fail "the rejected version declined a transfer"
say "version 2 rejected on $r2, never live: 300.00 still goes"

# 3. A low rule switched off is approved by nobody, and applied at once, with its request on the record.
low=$(version night-small transfer low live '{"op":"gte","path":"hourOfDay","value":23}')
r3=$(field request <<<"$low")
h3=$(request "$r3" | field hash)
ask "$bob" "/requests/$r3/approve" "{\"hash\":\"$h3\"}" >/dev/null
ask "$cy" "/requests/$r3/approve" "{\"hash\":\"$h3\"}" >/dev/null
until_approval night-small 1 given || fail "the low rule never went live"
off=$(version night-small transfer low off '{"op":"gte","path":"hourOfDay","value":23}')
r4=$(field request <<<"$off")
until_approval night-small 2 given || fail "switching the low rule off was not approved automatically"
for _ in $(seq 30); do [ "$(request "$r4" | field state)" = applied ] && break; sleep 1; done
auto=$(request "$r4")
[ "$(field state <<<"$auto")" = applied ] || fail "request $r4 is not applied: $auto"
[ -n "$(field approvedAutomatically change <<<"$auto")" ] || fail "request $r4 was not approved automatically"
say "night-small switched off on $r4: approved automatically ($(field approvedAutomatically <<<"$auto")), and applied"
say "the timeline of $r4: $(python3 -c 'import json,sys; print(", ".join(e["what"] for e in json.load(sys.stdin)["timeline"]))' <<<"$auto")"

# 4. Support sees Ada's account only while a grant Gil approved lives, and a second after it ends sees nothing.
sam_bank=$(token sam support); sam=$(token sam support bank-approvals); gil=$(token gil support bank-approvals)
sees() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $sam_bank" "localhost:8080/accounts/$1"; }
[ "$(sees ada)" = 404 ] || fail "support saw Ada's account with no grant"
grant=$(curl -sf "${json[@]}" -H "Authorization: Bearer $sam" -X POST localhost:8070/requests -d '{
  "kind":"bank.access-grant","subject":"bank/account/ada/viewer/sam","title":"sam to see ada for 20 seconds, ticket 1234",
  "before":"","after":"sam (support) may see the account ada for PT20S",
  "facts":{"access":"view","account":"ada","person":"sam","for":"PT20S","ticket":"1234"}}')
r5=$(field id <<<"$grant"); h5=$(field hash <<<"$grant")
[ "$(ask "$sam" "/requests/$r5/approve" "{\"hash\":\"$h5\"}")" = 409 ] || fail "Sam approved his own grant"
[ "$(ask "$gil" "/requests/$r5/approve" "{\"hash\":\"$h5\"}")" = 200 ] || fail "Gil's approval"
approved=$(date +%s)
for _ in $(seq 60); do [ "$(sees ada)" = 200 ] && break; sleep 0.5; done
[ "$(sees ada)" = 200 ] || fail "Sam never saw Ada's account with a grant"
[ "$(sees bob)" = 404 ] || fail "a grant for Ada's account showed Sam Bob's"
for _ in $(seq 30); do [ "$(curl -sf -H "Authorization: Bearer $gil" "localhost:8070/requests/$r5" | field state)" = applied ] && break; sleep 1; done
[ "$(curl -sf -H "Authorization: Bearer $gil" "localhost:8070/requests/$r5" | field state)" = applied ] || fail "grant $r5 is not applied"
say "Sam sees Ada's account, not Bob's, on $r5, approved by Gil and applied"
for _ in $(seq 20); do bank GET /accounts/ada/looks | grep -q '"role":"Support"' && break; sleep 0.5; done
bank GET /accounts/ada/looks | grep -q '"role":"Support"' || fail "Ada is not told support looked at her account"
bank GET /accounts/ada/looks | grep -q '"who":"' && fail "Ada's list of looks names someone"
say "and Ada's account says support looked, without a name"
left=$(( approved + 22 - $(date +%s) )); [ "$left" -le 0 ] || sleep "$left"
[ "$(sees ada)" = 404 ] || fail "Sam still saw Ada's account after the grant ended"
say "and a second after it ended, Sam sees nothing"

# 5. Sam acts as Ada on a signed-in page, with a grant Gil approved: sees her accounts, moves nothing, and is Sam again
# when it ends.
page=$(mktemp)
authorize=$(curl -sf -c "$page" -b "$page" -o /dev/null -w '%{redirect_url}' localhost:8080/login)
callback=$(curl -sf -o /dev/null -w '%{redirect_url}' -X POST "${authorize%%/authorize*}/approve" \
  --data "${authorize#*\?}&subject=sam&groups=support")
curl -sf -c "$page" -b "$page" -o /dev/null "$callback"
as_page() { curl -s -c "$page" -b "$page" "${json[@]}" -X "$1" "localhost:8080$2" ${3:+-d "$3"}; }
code_page() { curl -s -o /dev/null -w '%{http_code}' -c "$page" -b "$page" "${json[@]}" -X "$1" "localhost:8080$2" ${3:+-d "$3"}; }
[ "$(code_page POST /act-as/ada)" = 403 ] || fail "Sam acted as Ada with no grant"
acting=$(curl -sf "${json[@]}" -H "Authorization: Bearer $sam" -X POST localhost:8070/requests -d '{
  "kind":"bank.access-grant","subject":"bank/customer/ada/actor/sam","title":"sam to act as ada for 20 seconds, ticket 1234",
  "before":"","after":"sam (support) may act as ada, read-only, for PT20S",
  "facts":{"access":"act-as","customer":"ada","person":"sam","for":"PT20S","ticket":"1234"}}')
r6=$(field id <<<"$acting"); h6=$(field hash <<<"$acting")
[ "$(ask "$gil" "/requests/$r6/approve" "{\"hash\":\"$h6\"}")" = 200 ] || fail "Gil's approval of acting"
approved=$(date +%s)
for _ in $(seq 60); do [ "$(code_page POST /act-as/ada)" = 200 ] && break; sleep 0.5; done
[ "$(as_page GET /me | field subject)" = ada ] || fail "Sam is not acting as Ada"
[ "$(as_page GET /me | field actor)" = sam ] || fail "acting as Ada does not say Sam is there"
as_page GET /accounts | grep -q '"id":"ada"' || fail "acting as Ada, Sam does not see her account"
[ "$(code_page POST /accounts/ada/withdrawals '{"amount":{"value":"1.00","currency":"GBP"},"reference":"acting-1"}')" = 403 ] ||
  fail "a withdrawal while acting was not refused"
say "Sam acts as Ada on $r6: sees her account, and a withdrawal is refused"
left=$(( approved + 22 - $(date +%s) )); [ "$left" -le 0 ] || sleep "$left"
[ "$(as_page GET /me | field subject)" = sam ] || fail "Sam was still acting as Ada after the grant ended"
say "and when the grant ended, Sam was Sam again"

say "two approved before it declined; the rejected one never went live; the low switch-off applied at once; the grant lived and ended; acting was read-only and ended with its grant"
