# 0022 — Who may do what, in one place

## Problem

Once people sign in (spec 0019's Pocket ID, Pelican spec 0061), every service has to decide what each caller may do,
and spec 0021 has each service decide for itself in its own `Rules.kt`, from facts it holds: the bank knows who opened
an account, bank-checks knows who wrote a rule, bank-approvals knows who holds a grant. That is right for one service
and becomes wrong at three:

- **The same fact is copied.** "Bob, from support, may see Ada's account until 17:00" is granted in bank-approvals, and
  then the bank, the ops pages and anything else that shows an account each have to learn it and each check its expiry.
- **Nobody can answer the questions an auditor asks.** "Who can see account `acc-1` today?" and "What can Bob do?" need
  every service's rules read and their data joined by hand.
- **Taking access away means finding every copy.** Someone leaves `ops`; is that removed everywhere, and when?
- **Each service writes its own logic for groups, expiry and acting-as**, and three copies of it are three chances to
  get one subtly wrong.

## Not doing

- **Authentication.** Who is calling stays Pocket ID's and Pelican's (spec 0061). This service only answers what a
  verified caller may do.
- **Writing our own Zanzibar.** OpenFGA does the storage, the evaluation and the API; the bank writes a model and feeds
  it relationships.
- **Attribute rules with arithmetic.** "May pay up to £1,000 a day" stays the bank's own logic and bank-checks'. This
  answers relationships: owner, member, supporter, approver, with expiry as the one condition.
- **Dropping row-level security.** Spec 0021's database roles and row-level security stay, as the second fence: this
  decides in the service, the database keeps a query bug from leaking what the decision refused.
- **Changing who approves what.** bank-approvals still decides whether a grant is given; this service only learns the
  grant and enforces it.

## Shape

**`bank-access`: OpenFGA in the home cluster**, two replicas in the `bank` namespace, its store in a database of its
own, `access`, on `bank-db` (CloudNativePG), reachable only from the estate's services (a NetworkPolicy, and a
preshared key per service).

**One model, in version control**, the whole estate's answer to "who may":

```
model
  schema 1.1

type person
  relations
    define impersonator: [person with not_expired]     # who may act as this person, and until when

type group                                              # from Pocket ID: risk, ops, support, auditor, admins
  relations
    define member: [person]

type bank
  relations
    define ops: [group#member]
    define auditor: [group#member]

type account
  relations
    define bank: [bank]
    define owner: [person]
    define supporter: [person with not_expired]         # a grant approved in bank-approvals
    define payer: owner
    define viewer: owner or supporter or auditor from bank

type rule                                               # bank-checks' rules
  relations
    define bank: [bank]
    define author: [group#member]
    define approver: [group#member]
    define viewer: author or approver or auditor from bank or ops from bank

condition not_expired(current_time: timestamp, expires: timestamp) {
  current_time < expires
}
```

**Relationships come from events**, written by one small Kotlin consumer, `access-sync`, never by hand:

| event | relationship written |
|---|---|
| bank `Opened` (`bank.account-events`) | `person:ada owner account:acc-1` |
| bank `Closed` | the account's relationships removed |
| bank-approvals `Applied` for a support grant | `person:bob supporter account:acc-1`, `expires` from the grant |
| bank-approvals `Applied` for an impersonation grant | `person:bob impersonator person:ada`, with its `expires` |
| Pocket ID group membership, polled each minute | `person:bob member group:support`, and removals |

Because every relationship is derived from something already recorded, the store can be emptied and rebuilt from
offset zero and Pocket ID, and a relationship's origin is always an event with an id.

**Each service asks through its own `Rules.kt`**, which keeps its shape from spec 0021; only the body changes:

```kotlin
// bank/app/Rules.kt
class Rules(private val access: Access) {
    fun canView(who: Caller, account: AccountId) = access.check(who.actingAs, Account.viewer, account)
    fun canPay(who: Caller, account: AccountId) = !who.isImpersonating && access.check(who.actingAs, Account.payer, account)
    fun viewable(who: Caller): Set<AccountId> = access.listObjects(who.actingAs, Account.viewer)
}
```

- **`access-client`**, a Kotlin library published to the home Maven repository (spec 0020): `check`, `listObjects`,
  and the model's relations as typed constants (`Account.viewer`), checked against the model by a test so a renamed
  relation fails the build, not a request.
- **Acting as someone** is checked twice: `check(actor, impersonator, subject)` first, then the subject's own
  relation. What an impersonator may not do while acting (pay, change details) stays the service's rule, as above.
- **Each check has a 50 ms budget**, answers are memoised for the one request, and OpenFGA's own check cache holds
  answers for 10 seconds, which bounds how long a removed grant keeps working.
- **Fails closed.** No answer means no: the request is refused with 503 and `bank.access.unanswered` counts it.

**The auditor's questions, answered in one place.** The access page (`access.html`, beside the ops page) and two
endpoints, `GET /access/accounts/{id}/viewers` and `GET /access/people/{person}`, for ops, admins and auditors: *who can
see `acc-1`*, *what can Bob see*, and, for either, *why*: the relationship, the event that wrote it, and the approval
behind it. Each is read from the relationships OpenFGA holds, the way the model defines a viewer (the owner, a
supporter whose grant has not expired, a member of a group that audits the bank), rather than through ListUsers: a
read answers with the relationship itself, which is the reason. The owner's event is the account's opening in the
statements, and a supporter's approval the bank's own record of the grant. A question about an account is recorded as
a look at it.

**Decisions kept.** `access-client` publishes every refused check, and every check made by someone in a staff group or
acting as someone else, to `bank.access-decisions` (who, actor, relation, object, answer, when, from where). A
customer seeing their own account is not recorded; it would be almost everything, and it says nothing.

**Getting there without a flag day.** Services move from local `Rules.kt` to `bank-access` in shadow first: both are
asked, the local answer is used, and a disagreement is counted (`bank.access.disagreed`) and logged with both answers.
A rule is flipped to `bank-access` once it has disagreed zero times for a week; the account viewer first, then payer,
then rules and approvals.

## Why this shape

A relationship model fits what the estate actually decides: nearly every question is "is this person connected to this
thing, through ownership, a group or a grant, and is the grant still live". Zanzibar's design answers exactly that,
with "who can see this" as a query rather than an investigation. OpenFGA over SpiceDB: both are Zanzibar; OpenFGA
stores in Postgres the cluster already runs, has conditions for expiry, a Java SDK, a CLI that tests a model in CI,
and a gentler model language. SpiceDB's consistency tokens are stronger, and could be swapped in later behind
`access-client`. Deriving relationships from events rather than having each service write them means no dual write
to get wrong, and a store that can be rebuilt. The cost is a service in the path of nearly every request, which is
why it runs twice, fails closed, and is added in shadow.

The alternative is spec 0021 alone: rules in each service, facts in each service. Recommended as the start, and this
as where it goes once the second service needs the first one's grants.

## Depends on

- **Spec 0021**: `Caller` and `Rules.kt` in each service. This spec only replaces what is inside
  `Rules.kt`.
- **Spec 0019**: Pocket ID and its groups, and bank-approvals, whose `Applied` events carry grants.
- **Pelican spec 0061**: a verified caller, and the actor when acting as someone.
- **Spec 0015**: the bank's events on Kafka, which `access-sync` reads.
- **Spec 0020**: the home Maven repository, for `access-client`.

## Stack

- [x] **`access-model`** — the model, and `fga model test` cases for each relation: an owner sees, a stranger does not,
      an expired grant does not, an auditor sees but cannot pay, an impersonator acts only while the grant lives.
      Done when: the tests run in CI from the flake's OpenFGA CLI and fail on a broken relation.
      Done in bank-access (`model/model.fga`, `model/model.fga.yaml`): 6 tests, 36 checks, 7 ListObjects and 4
      ListUsers pass under `nix flake check`, which CI runs on GitHub's
      runners. A relation broken on purpose (`payer: owner or supporter`) fails it: support paying from the account a
      grant names, and the auditor's "who can pay acc-1", each named. bank-access/ holds it.
- [x] **`access-server`** — OpenFGA as a HelmRelease, two replicas and a PodDisruptionBudget, the `access` database,
      preshared keys in SOPS, a NetworkPolicy, the model applied by a Job on each change, alerts, and its memory in spec
      0010's table.
      Done when: the overlay renders, and in Docker the model loads and a written relationship answers a check.
      Done, as plain manifests rather than a HelmRelease (below). Every overlay renders, and the alerts pass promtool.
      bank-access's `scripts/server-docker.sh` runs OpenFGA 1.21 on Postgres with preshared keys, applies the model
      twice through the image the Job runs (the store made once, the model written as its newest version each time),
      writes Ada as acc-1's owner and Bob as its supporter until 2030, and checks: Ada sees and pays, Eve does not see,
      Bob sees until the grant ends and never pays; a check with no key, or a wrong one, is 401. Idle, 17 MiB.
- [x] **`access-sync`** — the consumer: account events, approval grants with expiry, Pocket ID groups, and a rebuild
      from offset zero.
      Done when: in Docker, opening an account makes its owner a viewer, an approved grant makes Bob one until it
      expires, and an emptied store rebuilt from zero answers every check the same as before.
      Done in bank-access (`sync/`). `SyncSpec`, against Kafka and OpenFGA in Docker: an opened account's owner views
      and pays and a stranger neither; Bob's approved grant lets him view for its four seconds and never pay, and is
      gone after; an act-as grant makes him Ada's impersonator; an auditor in Pocket ID's group sees the account, and
      taken out does not; and a store emptied of every relationship and rebuilt with `FROM_START` answers all 36 of its
      owner, stranger, support and auditor checks as before. Then the image, signed in to a broker running this
      repository's `setup.sh` as `access-sync`, wrote Ada as owner from an `Opened` the bank produced and Bob as
      supporter from a grant Approvals produced, and OpenFGA answered Ada and Bob yes, Bob paying no, Eve no.
- [x] **`access-client`** — `check`, `listObjects`, typed relations checked against the model, the per-request memo,
      the 50 ms budget, fails closed, metrics, and the decisions topic; published to home.
      Done when: a test with OpenFGA stopped is refused with 503, and a staff check lands on `bank.access-decisions`.
      Done in bank-access (`client/`), published locally as `bank-access-client` until spec 0020's repository runs.
      `ClientSpec`, against OpenFGA, Kafka and Apicurio in Docker: every relation the client names is in `model.json`
      (renaming `Account.payer` fails it, naming `account#pays`); an owner may, a stranger may not, one acting may only
      with a grant, and a memo asks once for five; with OpenFGA stopped, and with one answering after two seconds, each
      check is `Unanswered` with status 503 inside half a second, and `bank.access.unanswered` counts it; and an
      auditor's check, a refusal and a check by someone acting land on `bank.access-decisions`, read back by Apicurio's
      own deserializer with their groups, address and actor, while a customer seeing their own does not.
- [x] **`access-shadow`** — the bank's `Rules.kt` asks both, uses its own, counts and logs disagreements; an alert on
      any.
      Done when: the load test runs with zero disagreements, and a deliberately wrong relationship is counted.
      Done: `ShadowSpec` has Ada's and Eve's questions agreed on, then writes Eve as the account's owner, and Eve is
      still refused while `bank_access_disagreed_total{asked="view-account"}` reaches 1. In compose with the `access`
      profile, 200 transfers a second for two minutes asked bank-access 26,012 payer questions: 22,143 agreed, none
      disagreed, the ledger conserved, and 3,853 (15%) were unanswered. Those were the tail, not failures: the median
      check took 3 ms, but with three bank nodes, Kafka, two Postgres and OpenFGA on four cores, one in six took over
      the 50 ms budget. Measuring that on the cluster comes before `access-flip`, where an unanswered check is a 503.
      `AccessDisagreed` alerts on any, with a runbook section.
- [ ] **`access-flip`** — account viewer, then payer, answered by `bank-access`; `viewable` from ListObjects.
      Done when: the bank's pages and chaos runs pass with the flip on, and conservation holds.
- [x] **`access-audit`** — the who-can-see and what-can-they-see page, with each answer's reason, event and approval.
      Done when: a Playwright test shows Bob's grant on `acc-1`, with the approval it came from, and not after expiry.
- [ ] **`access-estate`** — bank-checks' rules and bank-approvals' votes asked through `bank-access`.
      Done when: a person outside `risk` cannot author a rule, and one outside a policy's groups cannot vote.

## Acceptance

```bash
fga model test --tests access/model.fga.yaml
kubectl kustomize deploy/overlays/home >/dev/null
scripts/access-docker.sh        # open, grant, expire, rebuild, stop OpenFGA: each answered as above
./gradlew build                 # the bank, with Rules.kt answered by bank-access
```

## Settled

Answered 2026-09-30, each taking its recommendation:

1. **OpenFGA or SpiceDB?** OpenFGA, for Postgres storage, conditions, the Java SDK and model tests in CI. SpiceDB stays
   possible later behind `access-client`.
2. **Where do relationships come from?** One `access-sync` consumer, from events, approvals and Pocket ID; never
   written by hand, and no service writes its own.
3. **What happens when it is down?** Every check fails closed, customers' own accounts included; two replicas and an
   alert keep that rare.
4. **The lag after opening an account?** Accepted: the owner relationship arrives through Kafka as the read models do.
5. **Its own repository?** Yes, `bank-access`, holding the model, its tests, `access-sync` and `access-client`. It
   needs creating.
6. **Who may change the model?** A pull request with the model tests passing and a reviewer from `admins`.
7. **Which decisions are kept?** Every refusal, and every check by staff or by someone acting as another, kept for good.
8. **How long may a revoked grant keep working?** At most 10 seconds, OpenFGA's check cache.

### Settled while building

- **Plain manifests, not a HelmRelease.** Every other service in `deploy/k8s` is plain, and kind's `up.sh` applies
  them with no Flux to read a HelmRelease; OpenFGA needs one Deployment, a migrate init container and a Service, which
  the chart would only wrap. `deploy/k8s/access.yaml`.
- **The model ships as an image.** bank-access's CI builds `bank-access-model` (the model, the `fga` CLI and
  `apply-model`) and pushes it to Zot; Flux's image automation moves the `access-model` Job's tag, and the Job, forced
  to be replaced since a Job cannot change, writes the model as the store's newest version. The cluster's model is
  main's, with no second repository for Flux to read.
- **The store is found by name, `bank`**, made by the first run of the Job; clients find it the same way, and use its
  newest model.
- **Its own login**, `access`, owning only its database: the first service with a role of its own, as spec 0021
  meant every service to have.
- **Grants are written on `Applied`, with the facts of their `Requested` and the time of their `ApprovalGiven`:** the
  bank owns what a grant may be, and only applies one it allows, so access-sync takes the bank's word. To have the
  facts at hand, the approval events are read from the start at every boot, in no consumer group; the topic is small.
  The account events are committed as they go, and read again from zero only to rebuild.
- **A grant given again lengthens, never shortens.** OpenFGA keeps one relationship per user, relation and object, so a
  later expiry replaces the one held; an earlier is ignored.
- **No `Closed`:** the bank's accounts have no such event, so nothing is removed for one yet. Expired grants stay in the
  store, answering no; sweeping them is a later entry's.
- **Groups are read for the groups Pocket ID has and the five the model names**, so one deleted in Pocket ID is emptied;
  where no Pocket ID runs (kind, compose), access-sync leaves groups alone and says so.
- **One replica, its own Kafka user**, `access-sync`, reading the two topics and its own group, and nothing else.
- **The client answers, the service refuses.** `Answer.Unanswered` carries the 503 a service answers with; turning it
  into the response is each service's, as the `access-shadow` and `access-flip` entries do in the bank.
- **One acting is two checks**, the grant to act and then the subject's relation, each within the 50 ms budget.
- **Decisions are Protobuf through Apicurio** (`AccessDecision` in lark-bank-events), as every estate topic is, and
  sent without waiting: a decision is never held up by the record of it. Kafka lets the bank, the checks and
  Approvals write the topic.
- **The shadow asks after answering, off the request's thread**, at most 256 questions at once (more are counted as
  skipped); a request is never slower for it. A disagreement is asked again two seconds later before it counts, as a
  relationship can trail the event it comes from. Its decisions are not recorded: the bank's own access log keeps
  what was decided until the flip.
- **The bank builds bank-access's client in the same Gradle run**, from `-PaccessSource`, as it does lark and
  pelican, in CI and in the Nix package alike (bank-access is a flake input). For that the events became a build of
  their own, included by the bank's: Gradle cannot substitute a root build's subproject into an included build.
