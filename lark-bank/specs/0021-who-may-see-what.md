# 0021 — Who may see what

## Problem

The bank does not know who is calling it. Spec 0006 said so on purpose ("anyone can open any account id"), and it is
still true of every endpoint: `GET /accounts/acc-1` answers anyone who can reach the port, `owner` is whatever string
the caller typed into `OpenAccount`, and a transfer can be sent from an account by someone who does not own it. The ops
pages and `/ops/stream` answer anyone too.

Behind the API it is no better:

- **One database user for everything.** The bank's journal, its read models and its projections all use `bank`, and
  anyone holding that password (a laptop's `psql`, a debugging session) reads every customer's statement.
- **Nothing inside the cluster is fenced.** Kafka listens in plaintext with no users, so any pod may read every
  account's events or publish into `bank.*`. Grafana has one admin login. There are no NetworkPolicies.
- **Support has no way to help one customer without being able to see all of them.** The only way to look at Ada's
  account is the way anyone looks at anyone's, and nothing records who looked.

Spec 0019 brings Pocket ID for people to sign in, and Pelican spec 0061 brings a verified caller into a handler. This
spec is what the bank does with it.

## Not doing

- **A central authorisation service.** That is spec 0022, which replaces what is inside `Rules.kt` once more than one
  service needs another's grants. This spec keeps the rules in the bank, with the bank's facts, in a shape 0022 can
  take over without touching a handler.
- **Staff opening accounts or moving money for a customer.** Staff may look; only a customer moves their own money.
- **Customer sign-up, KYC or account recovery.** A customer is a person in Pocket ID; how they got there is not the
  bank's concern here.
- **Fine-grained limits** ("may pay up to £1,000 a day"). Those are bank-checks' rules (spec 0018).
- **Encrypting data at rest** beyond what CloudNativePG's volumes already are.

## Shape

**Every endpoint takes the caller**, as an input like a path parameter (Pelican spec 0061):

```kotlin
// bank/api/Caller.kt
data class Caller(val subject: String, val name: String, val groups: Set<String>, val actor: String? = null) {
    val actingAs get() = subject                  // whose data this request is about
    val isImpersonating get() = actor != null      // someone else is really there
    val isStaff get() = groups.any { it in STAFF }
}

val caller = authenticated(bearerOrSession) { id -> Caller(id.subject, id.name, id.groups, id.actor) }

val getAccount = endpoint(caller, accountId) {
    get("accounts" / accountId)
    json<AccountView>().orFail(accountMissing)
}
```

A request with no caller, or a bad token, is refused with 401 before any handler runs. `/api-docs`, `/health` and
`/metrics` stay open; everything else takes `caller`.

**Every decision is one line in one file**, `Rules.kt`: small, pure functions of the caller and the facts the bank
already has. A handler asks one of them and nothing else decides:

```kotlin
// bank/app/Rules.kt
class Rules(private val grants: Grants) {
    fun canView(who: Caller, account: Balance) =
        account.owner == who.actingAs || grants.supports(who.subject, account.id) || "auditor" in who.groups

    fun canMove(who: Caller, account: Balance) = account.owner == who.actingAs && !who.isImpersonating
    fun canSeeOps(who: Caller) = "ops" in who.groups || "admins" in who.groups
}

// bank/api/Handlers.kt
getAccount handledOrFail { (who, id) ->
    val account = bank.balance(id) ?: return@handledOrFail accountMissing(id)
    if (!rules.canView(who, account)) accountMissing(id) else ok(view(account))
}
```

- **Someone else's account is missing, not forbidden**: 404, the same as an account that does not exist, so a caller
  cannot learn which ids are real.
- **The owner is the caller.** `OpenAccount` loses its `owner` field; `Opened.owner` is `who.actingAs`. Opening while
  impersonating is refused.
- **`GET /accounts`** lists the caller's own accounts, from `account_balance.owner`.
- **A table test is the specification**: every endpoint, for each of *owner*, *stranger*, *support with a grant*,
  *support without*, *auditor*, *ops*, *impersonating*, with the status it must answer. A new endpoint without a row
  fails the build.

**Who is who** comes from Pocket ID's groups, set by an admin there:

| group | may |
|---|---|
| none (a customer) | open accounts; see and move money in their own |
| `support` | see an account only with a live grant for it; act as a customer only with a live impersonation grant |
| `ops` | the ops pages, the ledger totals, transfer states; no statements, no customer names |
| `auditor` | read every account and the access audit; change nothing |
| `admins` | what `ops` may, and the Grafana admin role |

**Grants are approved requests** in bank-approvals (spec 0019): a support person asks for *view `acc-1` for 30 minutes,
ticket 1234*, or *act as Ada for 30 minutes*, with a reason; someone else in `support` or `ops` approves; on `Applied`
the bank's projection writes it to `access_grant (person, kind, account_id, subject, expires_at, approval_id)`. Grants
expire on their own; the approver may end one early, which is another approval event.

**Acting as a customer** is how support sees what Ada sees:

- On a page, `/act-as/ada` checks for a live grant and swaps the session for one whose subject is Ada and whose actor
  is Bob (spec 0061's `oidc.actAs`), ending no later than the grant.
- Every page shows a banner while it lasts: *Bob (support) is acting as Ada until 14:30. Stop.*
- It is read-only: `canMove` refuses anything that changes money or details while impersonating.

**Every look by staff is recorded.** Each request answered for someone in a staff group, or while impersonating, is
written to `access_log` and published to `bank.access-events`: who, acting as whom, which endpoint, which account,
the answer, when, the address it came from, and the grant it used. Refusals are recorded for everyone. A customer sees
on their account page who has looked at it: *Support, 14:02, viewed statement*, with names kept for the auditor.

**The database is the second fence.** Each service and each purpose gets its own Postgres role, and the read models a
customer's request reads are behind row-level security:

| role | may |
|---|---|
| `bank_journal` | the journal databases, read and write |
| `bank_projector` | write the read models (bypasses row security, as their only writer) |
| `bank_reader` | read the read models, row security on |
| `checks`, `approvals`, `pocket_id` | their own databases only |
| `auditor_ro` | read the read models, row security on, for a person at `psql` |

```sql
alter table account_balance enable row level security;
create policy visible on account_balance for select to bank_reader, auditor_ro using (
    owner = current_setting('bank.caller', true)
    or exists (select 1 from access_grant g where g.person = current_setting('bank.caller', true)
               and g.account_id = account_balance.account_id and g.expires_at > now())
    or current_setting('bank.auditor', true) = 'on'
);
-- statement_line and transfer_status: the same, through account_balance
```

The bank sets `bank.caller` with `set local` in each read's transaction, so a query that forgot a `where` returns the
caller's rows and no one else's. There is no password for `bank_projector` outside the bank's own secret.

**The cluster, fenced cheaply:**

- **Grafana** signs in through Pocket ID; `admins` are admins, `ops` are editors, and nobody else is let in.
- **Kafka** gets SASL/SCRAM users, one per service, with ACLs: the bank writes `bank.*`; bank-checks reads
  `bank.account-events` and writes `checks.*`; bank-approvals writes `bank.approval-events`; nothing else.
- **NetworkPolicies**: deny by default in `bank`, then allow only what each service calls (the bank to its databases,
  Kafka and the check; the check to its database and Kafka; ingress to the bank's pages and bank-checks' admin).
- **The ops pages and `/ops/stream`** take `caller` and ask `canSeeOps`.

## Why this shape

Rules in the bank, beside the facts they need, make authorisation ordinary code: read in one file, tested in one table,
with no new service to run or to be down. A caller as an input rather than a filter is what makes forgetting a check a
compile error or a failed row in the table rather than a silent hole. Row-level security below it is cheap and catches
the mistake the rules cannot: a query that returns too much. Grants through approvals, rather than a `support` group
that simply sees everything, mean support access is to one account, for a reason, for a while, agreed by someone else
and recorded, which is what a real bank would be asked to show.

The alternative is starting with spec 0022's central service. It answers "who can see what" across the estate, but
puts a new service in the path of every request before a second service needs it. Recommended: this first, shaped so
0022 replaces only the bodies in `Rules.kt`.

## Depends on

- **Pelican spec 0061**: the caller input (`spec-0061-caller`), token verification (`spec-0061-oidc`), sign-in for
  pages (`spec-0061-pages`) and the actor (`spec-0061-actor`). The bank waits for each; there is no workaround worth
  writing, because a header the bank trusts would be the hole this spec closes.
- **Spec 0019**: Pocket ID and its groups (its `identity` entry), and bank-approvals for grants (its saga and events).
- **Spec 0015**: events on Kafka, for `bank.access-events` and the approval events the bank reads.

## Stack

- [x] **`caller`** — `Caller`, every endpoint takes it, `Rules.kt`, 404 for others' accounts, the owner from the
      caller, `GET /accounts`, `inMemory(callers = ...)` in tests, and the endpoint × caller table.
      Done when: the table test passes, and a request without a token is 401 on every endpoint but the open four.
      Done: `CallerTableSpec` calls every endpoint as nobody, the owner, a stranger, support, an auditor, ops and
      someone acting as the owner, and fails if the document describes an endpoint the table has no row for. The open
      four are `/health`, `/ready`, `/metrics` and `/openapi.json` (with `/api-docs`, its page). Tests call with real
      tokens from the test issuer, verified over HTTP as Pocket ID's will be, rather than `inMemory(callers = ...)`.
- [x] **`sign-in`** — the pages sign in through Pocket ID, show who is signed in, and sign out; the load test and chaos
      runs sign in with a test issuer of their own, trusted only in their overlay.
      Done when: Playwright signs in to a stub provider, opens an account, and cannot open a stranger's; the load
      test's numbers are within 5% of run 8's.
      Done, and measured rather than held to the 5%. The test issuer is the `issuer` module (published as
      `lark-bank-issuer`), run by compose and kind and deleted by the home overlay, which names Pocket ID. Playwright
      signs in through it, opens an account, is shown "No account" for a stranger's, and signs out. Measured in Docker
      against `main` without callers, transfers at 400 a second for 60 s, fresh stacks, alternating: p50 110–173 ms
      without (seven runs of main on this host spread that wide) and 117–193 ms with; p99 about 1.2 s without and 1.35 s
      with; nothing failed in any run and every ledger conserved. A flight recording of bank-1 under the same load puts
      callers, tokens and owners at 1.2% of its CPU samples, and in that pair the p50s were 154 and 155 ms. Two caches
      keep it there: each node remembers an account's owner (set once, when it opens) and a verified token until it
      expires. The chaos run conserved, with 6,223 of 84,000 failed against run 8's 6,150, fault by fault alike.
- [x] **`staff`** — the groups, `canSeeOps`, the ops pages and stream behind it, `access_grant` projected from
      approval events, support seeing one account while a grant lives.
      Done when: a support caller sees `acc-1` with a grant, gets 404 without one, and gets 404 a second after it
      expires.
      Done. `GrantsSpec` sends a grant's events to Kafka as bank-approvals writes them: support gets 404 before it, sees
      the account and its statement once it is approved (another account of the same customer's stays 404, and so does
      the account for someone else in support), Approvals is told `applied` with a bearer token, and a second after the
      grant ends it is 404 again. Asked for someone else, for five hours, or by someone outside support, a grant is never
      given and Approvals is told `apply-failed` with why. `CallerTableSpec` has a column for support with a grant. And
      `scripts/asks-docker.sh` runs it against the real Approvals: Sam (support) asks for 20 seconds on Ada's account,
      may not approve his own, Gil approves, Sam sees Ada's account and not Bob's, the request is applied, and two
      seconds after the grant ends Sam sees nothing. The ops pages are static; what they show comes from `/ops/stream`
      and the ops endpoints, which `canSeeOps` already guarded (`caller`).
- [x] **`act-as`** — `/act-as/{subject}`, the banner, read-only while acting, and the session ending with the grant.
      Done when: Playwright acts as Ada, sees her statement and the banner, is refused a withdrawal, and is back to
      themselves when the grant ends.
      Done. `ActAsSpec`: Sam (support) signs in, is refused acting as Eve with no grant, acts as Ada with one, sees the
      banner ("sam (support) is acting as ada until …") on every page, her accounts and her statement, is refused a
      withdrawal as read-only, and once the grant ends is Sam again with nothing of hers; Stop ends it at once; Gil in
      support cannot use Sam's grant; and a token acting as Ada with no grant behind it sees nothing. Through Kafka,
      `GrantsSpec` gives an act-as grant from Approvals' events; and `scripts/asks-docker.sh` runs it all against the
      real Approvals with a signed-in session, curl standing in for the browser.
- [x] **`access-log`** — `access_log`, `bank.access-events`, every refusal and every staff look, and the customer's
      "who has looked" list.
      Done when: Ada's page lists Bob's look, and the topic carries it with the grant's approval id.
      Done. `AccessLogSpec`: Bob (support, with a grant) views Ada's account and statement; the auditor sees both looks
      by name with the grant's approval id; Ada's page, in Chromium, lists "Support, …, viewed the statement" and
      "viewed the account" and names nobody; and `bank.access-events`, read by Apicurio's own deserializer, carries the
      look with `grant_approval_id`, status 200 and Bob's groups. Refusals (a stranger's 404, support's without a
      grant) are recorded for the auditor, a customer's own looks are not, and neither reaches the customer's list.
      `scripts/asks-docker.sh` checks it against the real Approvals too: 14 looks recorded, all 14 published.
- [x] **`db-roles`** — the roles, their secrets in SOPS, the row-level policies, `set local bank.caller` on every read.
      Done when: as `bank_reader` with `bank.caller` set to Ada, `select * from statement_line` returns only her rows,
      and the chaos runs still conserve.
      Done. `DbRolesSpec`: as `bank_reader`, `select account_id from statement_line` with no `where` returns Ada's two
      accounts for Ada, Bob's for Bob, nothing for Eve or for nobody, the one account a grant names for Sam (support),
      and every account for `auditor_ro` only once `bank.auditor` is on; balances and transfers' states the same way, a
      transfer seen by either end's owner; and the reader can neither write nor read the journal. The bank's own
      `statement` read, asked for Ada's account as Eve (as a handler that forgot its rule would), returns nothing. The
      chaos run conserved: 84,000 transfers at 200 a second through every fault, 6,498 failed against run 8's 6,150 and
      the last run's 6,223, and every currency's books balanced at rest.
- [x] **`fences`** — Grafana through Pocket ID, Kafka users and ACLs, NetworkPolicies, and a chaos fault that tries to
      read `bank.account-events` from an unlisted pod.
      Done when: the overlay renders, the fault's read is refused, and every service still does its job.
      Done, in Docker; the NetworkPolicies and Grafana's sign-in render but have not run, since this machine runs no
      cluster. Every overlay renders. The chaos run's sixth fault reads `bank.account-events` from a fresh container on
      compose's network: with no user it reads nothing, as Approvals it is refused the topic, as the checks under a group
      not theirs it is refused the group, and as the checks under their own group it reads 5, so each refusal is the
      fence; the run conserved, 84,000 transfers with 6,729 failed through the faults. Every service did its job over
      SCRAM: `scripts/asks-docker.sh` (the checks asking Approvals, approvals heard by the checks and the bank, grants
      and acting, the access log published, 15 of 15) and `scripts/checks-docker.sh` (a rule declining a transfer, a
      withdrawal flagged on `checks.flags`), with no authentication or authorization error in any service's log.
      `PocketIdSpec` runs the setup script that now makes Grafana's client.

## Acceptance

```bash
./gradlew build                          # the endpoint × caller table among the tests
scripts/chaos-docker.sh                  # conserves, with sign-in, roles and ACLs on
kubectl kustomize deploy/overlays/home >/dev/null
```

## Settled

Answered 2026-09-30, each taking its recommendation:

1. **Someone else's account: 404 or 403?** 404, the same as an account that does not exist, so ids cannot be probed.
2. **May support act while impersonating?** No: only look. Moving money or changing details is refused.
3. **How long does a grant last?** 30 minutes by default, 4 hours at most, one account or one person each; ended early
   by the approver or the person who asked.
4. **What does a customer see of who looked?** The role, the time and what was looked at; names only in the audit.
5. **How do the load test and chaos runs sign in?** A test issuer whose key the load test holds, trusted only by the
   `loadtest` overlay and Docker compose, never by `home`.
6. **Row-level security for the bank's own reads too?** Yes: the bank sets `bank.caller` on every read, and people at
   `psql` are fenced the same way.
7. **Accounts whose owner is a free string today?** Not kept: they are demo and load-test data, and the load test's
   owners become its test issuer's subjects.
8. **Kafka: SASL/SCRAM or mutual TLS?** SASL/SCRAM, set when the broker's storage is formatted, passwords in SOPS.

### Settled while building

- **A grant is a request of kind `bank.access-grant`**, asked of Approvals by the person who will hold it, with the
  subject `bank/account/{account}/viewer/{person}` and the facts `access=view`, `account`, `person`, `for` (an ISO
  duration, 30 minutes if absent, at most four hours) and a `ticket`. Approvals' policy has one person in `support` or
  `ops` approve it, never the asker, within the hour. The bank checks the facts itself when it hears `Requested`, since
  it owns what a grant may be: a request the policy let through but the bank would not give is answered `apply-failed`
  with why.
- **Given on `ApprovalGiven`, not on `Applied`.** The bank is the owning service: it writes the grant, then says
  `applied`, as the checks do with rule versions. Waiting for `Applied` would wait on its own answer. The grant runs
  from when it was approved, so a bank that hears it late gives what is left of it.
- **The bank reads `bank.approval-events` in the group `lark-bank-access`**, every node its share of the partitions,
  and never creates the topic: Approvals makes it with its own partitions.
- **Ending a grant early is not built yet.** It is another approval event, and needs Approvals to have one; acting
  already checks its grant on every request, so ending one ends acting at the next request.
- **Acting is `POST /act-as/{subject}` and `DELETE /act-as`,** not a `GET` a link could trigger: the home page's
  form posts it, and the answer sets the session's cookie. A token cannot act, since it has no session to swap: both
  answer 403 to one, and the caller table says so.
- **A grant to act as someone is the same request with `access=act-as` and `customer`,** given as `access_grant` rows
  of kind `act-as` naming the customer as `subject`. Nobody acts as themselves.
- **Every request while acting asks for the grant again** (`Rules.mayActAs`), not only the one that starts it: a
  session says until when, but a token with an actor says nothing, and a grant could end before its time.
- **Looks are recorded by a filter after the answer** (`recording` in `api/Looks.kt`), off the request's thread, so
  a staff look answers as fast as anyone's. A look the database would not take is still answered: it is counted,
  logged and alerted on (`LookUnrecorded`), since refusing a support call because the log is down helps nobody.
- **`bank.access-events` is fed from `access_log` as an outbox:** every node, every second, takes the oldest
  unpublished rows with `for update skip locked`, sends them, and marks them, so each is sent once from here and a
  crash resends rather than loses. Keyed by account, or the caller's subject when there is none; the row's id is
  what a consumer dedupes on.
- **A customer is told of looks that saw something**, by role, not attempts that saw nothing, and not their own; the
  auditor sees all of it, names and grants included, on the same endpoint, `GET /accounts/{id}/looks`.
- **Two readers, not five roles.** `bank_reader` (the bank reading a customer's request) and `auditor_ro` (a person
  at psql) are the roles; the bank's own login stays the read models' owner and their only writer, which row security
  does not restrict, so it is the spec's `bank_projector` without another name, and its journal login is the spec's
  `bank_journal`. The fence is against a query that returns too much, as the spec says, not against someone holding
  the bank's password: anyone may set `bank.caller`.
- **The bank becomes `bank_reader` per transaction** (`set_config('role', …, true)` with `bank.caller` and
  `bank.auditor`, in one statement), rather than holding a second pool: one round trip more per customer read, and no
  second password. Its login is a member, by CloudNativePG's `managed.roles` at home and by the migration elsewhere; a
  node that cannot become it refuses to start.
- **The policies apply to everyone but the owner**, not to named roles, so they hold before the operator has made the
  roles; the roles' grants are ensured at every start (`runAlways`), since the operator may make them after the
  first migration.
- **Ops' totals and the bank's own machinery read as the owner:** the ledger, transfer counts, the sweeper and the
  gauges are not a customer's request, and row security would count only what one caller may see.
- **Kafka's users are made by the broker's own pod**, over a listener on 127.0.0.1 that only it can reach, rather than
  when its storage is formatted (settled 8): the apache/kafka image formats on first start, and the setup runs again
  at every start, so a password changed in SOPS is a restart, not a reformat. The controller listener is on 127.0.0.1
  too, so the only connections nobody signs in to are inside the pod; `User:ANONYMOUS` is the super user there.
- **ACLs are literal topics and groups**, not `bank.*` as the shape says: `bank.approval-events` is Approvals', and a
  prefix would let the bank write it. The bank writes its three topics and reads approvals; the checks read accounts'
  events and approvals and write `checks.flags`; Approvals writes its own.
- **kafka-clients 3.9.2 in the bank and Approvals**, over Lark's 3.8: 3.8's SCRAM sign-in asks `Subject.getSubject`,
  which JDK 25 refuses, so every sign-in failed. Lark's own pin should move too.
- **NetworkPolicies are for traffic in only.** Who may call each pod is the fence; traffic out is left open, since a
  pod's calls out land on a pod whose policy decides. Home only: kind's CNI does not enforce them.
- **Grafana at home only.** Kind and compose keep their anonymous Grafana; home's signs in through Pocket ID, refuses
  anyone with no role (`role_attribute_strict`), and is behind its ingress, not a node port.
- **Moving money while acting is 403, not 404:** the account is plainly there to whoever is acting, and the message
  says why (read-only). Deposits, withdrawals and transfers declare it.
