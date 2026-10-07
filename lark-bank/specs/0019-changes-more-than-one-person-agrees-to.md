# 0019 — Changes more than one person agrees to

## Problem

One admin can make a screening rule live on their own (spec 0018). From the next transfer, every payment the rule
covers is declined, or every one it no longer covers goes through, and nobody else has looked. A bank does not let one
person change what stops money: a second person, or several, reads the change and agrees before it takes effect,
and who agreed is on the record.

Rules are the first such change, not the last. Raising an account's limits, reversing a transfer by hand, adding an
admin, and changing the screening timeout all want the same thing: someone proposes, named others approve, and only
then does the owning service act. Built once, into the rule wizard, it would be built again for each.

## Not doing

- **What any change is.** Approvals never understands a rule, a limit or a transfer. The service that owns the
  change describes it for the approvers, and carries it out once approved.
- **Carrying out the change.** Approvals says *approved*; the owning service acts on that, as it would have acted on
  its own before.
- **A general workflow engine.** No branches, loops, scripts or timers beyond one expiry. A request is a proposal and
  a set of votes.
- **Notifications by email or chat.** The pages show what is waiting on whom; mail or Slack is a later spec.
- **Break-glass.** No way round an approval for an emergency: an admin switching a rule off asks like any other
  change (see Open questions).

## Shape

**The words.** A *request* asks approval for one *change* to a *subject*, raised by its *requester* in the *service*
that owns the subject. A *policy* says, for one *kind* of change, who may approve it, how many must, and when it needs
nobody. An *approver* reads the change as a *diff* against what is in force now, and *approves* or *rejects* it;
anyone on the request may *comment*. A request ends *applied*, *rejected*, *withdrawn* by its requester, or *expired*;
one approved but not yet applied is *approved*.

**A service of its own**, `bank-approvals`, in a repository of its own, deployed beside the bank and the check with
its own database on `bank-db-0`. Built as the bank is: Kotlin on Lark and Pelican.

**A request is a saga**, as a transfer is (spec 0001): a Lark persistent entity per request id, each step an event in
Lark's journal, the next step read off its state, so a request recovered on another node carries on from its last
event, and its history is the record.

```
Requested ──▶ AutoApproved (policy says nobody need look) ───────────────────────────┐
    │                                                                                   ▼
    └▶ AwaitingApproval ─▶ Commented* ─▶ Approved (ada) ─▶ Approved (bob) ─▶ ApprovalGiven ─▶ Applied
                             │                                                    │
                             ├▶ Rejected (bob, "the bound is too low")            └▶ ApplyFailed (why) ─▶ retried
                             ├▶ Withdrawn (by its requester)
                             ├▶ Superseded (a newer request for the same subject)
                             └▶ Expired (no decision in 7 days)
```

- **It drives the change to the end, not just the vote.** Once enough have approved, the saga tells the owning
  service (`ApprovalGiven` on Kafka) and waits for it to say `Applied` or why not, asking again on a timer as a
  transfer asks for a leg, until it has. So the record says not only that two people agreed, but that the change they
  agreed to is the one now in force, and when it took effect.
- **One live request per subject.** A second request for the same rule supersedes the first, whose votes stay in its
  history: nobody approves a change that is no longer the one on the table.
- **The requester may withdraw it** until it is approved; nobody may after.

**The diff is the heart of the page.** The owning service sends two renderings of the subject: as it is *now*, and as
it would be *after*, each as text an approver can read, with anything else it wants shown beside them:

```http
POST /requests
{ "kind": "checks.rule-version", "subject": "checks/rule/large-transfer",
  "title": "large-transfer, version 4",
  "before": "amount is at least 1000",
  "after":  "amount is at least 500 and not (to is \"trusted\")",
  "impact": { "declinedLastSevenDays": { "before": 7, "after": 31, "of": 1200 } },
  "facts":  { "change": "tighten", "severity": "high" },
  "link":   "https://checks.example.internal/#/rules/large-transfer" }
→ 201 { "id": "req-…", "state": "awaiting-approval", "needed": 2, "approvers": [ "risk" ] }
```

Approvals shows `before` and `after` as a side-by-side diff, word by word, with the impact beside it; for a new
subject, `before` is empty. It never parses either: the owning service knows what its change means, and Approvals
shows what the approver will be agreeing to. The two texts, hashed, are what is approved (the audit below).

**Approve, reject, comment.** On a request's page an approver can:

- **approve**, with an optional comment;
- **reject**, with a comment required, which ends the request;
- **comment**, without voting, to ask a question or answer one, in a thread on the request that the requester and
  every approver see, each comment an event like a vote.

A person may change their approval to a rejection until the request ends; the first stays in the history. The
requester never approves their own request, and nobody counts twice.

**Policies, including when nobody need look**, as configuration in the approvals repository, reviewed and merged like
code and shipped by Flux (spec 0017), so a change to who approves what goes through a pull request and is in
`git log`. Each request records the policy's version it was decided under:

```yaml
# policies.yaml
- kind: checks.rule-version
  approvers: [ risk ]
  needed: 2
  expiresAfter: 7d
  # Changes that need nobody: each a condition on the facts the owning service sends.
  autoApprove:
    - when: { change: switch-off, severity: low }        # a low rule switched off lets a little more through
    - when: { change: describe-only }                    # the name or severity changed, not what it declines
- kind: checks.rule-off
  approvers: [ risk, ops ]
  needed: 1
- kind: bank.account-limit
  approvers: [ ops ]
  needed: 1
  autoApprove:
    - when: { direction: lower }                         # lowering a limit only stops more
```

An auto-approved request is still a request: it is `Requested`, then `AutoApproved`, naming the policy version and the
condition that allowed it, then applied and recorded like any other. Nothing skips the record, only the wait. A
condition matches the `facts` exactly, all of its keys; nothing else is approved automatically, and a kind with no
policy needs two approvers from `admins`.

**Answered on Kafka.** Each request's events go to `bank.approval-events`, keyed by request id, in the published
language of spec 0015 (Protobuf, Apicurio, `FULL_TRANSITIVE`). The owning service acts on `ApprovalGiven` and
`AutoApproved`, answers with `POST /requests/{id}/applied` (or `/apply-failed`), and treats `Rejected`, `Withdrawn`,
`Superseded` and `Expired` as the change not happening. At least once, as every consumer of the bank's events: the
owning service applies a request id once.

**Everyone signs in through OIDC.** Pocket ID runs at home (one small container, passkeys, groups, its data in a
database of its own on `bank-db-0`), and holds the people and their groups (`risk`, `ops`, `auditor`, `admins`);
Approvals, the check's wizard and later the bank's ops pages sign in through it, so a person is the same person
everywhere and the check's own Access (passwords in a Secret) goes. Every act is recorded with the provider's subject
id and the name it gave at sign-in.

**The pages**: *waiting on me* (requests I may approve and have not), *asked by me*, *recent* (everything, for
auditors), and one request: its title, the diff, the impact, the link, the policy and how many more are needed, and
one timeline of every approval, rejection and comment, in order.

**The audit is the point.** Approvals exists so that anyone can later answer *who agreed to what, when, having seen
what*. So:

- **Every act is an event, kept for good.** Requested, auto-approved, each approval, rejection and comment, each
  approval replaced, withdrawn, superseded, expired, applied and failed to apply: in the journal, never pruned, never snapshotted away, backed up and restore-drilled
  with the rest (spec 0010). Nothing is edited or deleted; a correction is another event.
- **Refusals are on the record too.** A vote the rules turn away (the requester approving their own change, a second
  vote from one person, a vote from outside the policy's groups, a vote after the end) is an event, `VoteRefused`,
  with why. An audit that shows only what succeeded hides the attempts that matter most.
- **Each event says who, when and from where**: the person, their session, the request's source address and user
  agent, and the server's time. Never a time the caller sends.
- **What was approved cannot change after.** A request carries the SHA-256 of its `before`, `after` and `facts` as
  asked, and
  each vote records the hash it was cast on. The owning service acts only if the hash it holds is the one approved, so
  a change swapped after the votes is refused, not carried out.
- **Tampering shows.** Each event carries the hash of the one before it in its request, a chain from `Requested` on,
  and every night the heads of every chain changed that day are hashed into one digest, kept in its own table and
  shipped offsite with the backups (spec 0010's WAL copy). `GET /approvals/audit/verify` walks every chain and names
  the first event that does not match.
- **The change points back.** The owning service records the request id on what it changed (the rule version keeps
  the approval that made it live), so the audit goes both ways: from a live rule to the people who agreed, and from a
  person to everything they agreed to.
- **Read as well as kept.** Pages and an endpoint answer the auditor's questions: one request's timeline, everything
  one person asked for or voted on, everything about one subject (`checks.rule-version/large-transfer`), all within
  a date range; exported as CSV and JSON, the export itself recorded as an event.
- **Published.** The same events go to `bank.approval-events`, so an outside system can keep its own copy.

**The first kind: a rule version in the check.** Making a version live in the wizard (spec 0018, step 5) asks for
approval instead: the version is stored as *proposed*, and the check requests approval with the live version's words
as `before`, the new version's as `after`, the dry run's counts as `impact`, and facts saying what kind of change it
is (`tighten`, `loosen`, `switch-off`, `describe-only`, worked out by the check from the two versions) and the rule's
severity. On `ApprovalGiven` or `AutoApproved` the check makes it live and says `Applied`. The wizard shows it
waiting, with who has approved and a link to the request.

## Why this shape

A request that Approvals holds and the owning service carries out keeps each context to its one job (spec 0018's map):
Approvals decides *whether*, never *what*, so a new kind of change is a policy and a caller, not a change here. The
saga carries a request past the vote to the change being applied, because "two people agreed" is half the audit: the
other half is that the thing now in force is the thing they saw. Policies as reviewed configuration keep the rules
about who approves where the other rules about the bank's running are, in Git, and auto-approval keeps the people's
attention for the changes that deserve it, without taking anything off the record.
Event-sourced requests make the audit trail the journal itself. Kafka for the answer means the owning service can be
down when the last vote lands and still act, and several services can listen; a webhook back to the caller would
need retries and signing of its own. The alternative is approvals inside each service (a column and a page in the
check): simpler for one kind, and the second kind is where it costs twice.

## Depends on

- Spec 0015's publishing, for `bank.approval-events`; spec 0018's check, for the first kind.
- **Pocket ID at home**, for OIDC: the first entry of this stack. Tests use a fixed issuer of their own.
- A Pelican piece for OIDC sign-in (the authorization-code flow, sessions, and a verified identity on each request) if
  Pelican has none: a spec there, with the service's own filter as the workaround until it lands.
- Nothing in Lark or Pelican: a persistent entity, Pelican endpoints and pages, and the Kafka publisher all exist.

## Stack

In the bank's deploy (lark-bank):

- [x] **`identity`** — Pocket ID in `deploy/k8s`, its database, the groups (`risk`, `ops`, `auditor`, `admins`), and
      its memory in spec 0010's table.
      Done when: the overlay renders, and in Docker a person signs in to Pocket ID and a test client gets their
      groups in an ID token.
      Done, not yet deployed. Pocket ID v2.16.0 is in the home overlay, not `deploy/k8s`, since kind keeps the test
      issuer (bank spec 0021):
      - Its database `pocket_id` is on `bank-db-0`, and everything it keeps is in that database, so the WAL copy
        covers it.
      - Its secrets are in SOPS, with a template in `bank.example.yaml`.
      - A `pocket-id-setup` Job runs `identity/setup.sh` through the admin API. It makes the groups (`services` added,
        for the owning services of `approvals-saga`) and the pages' clients, `bank-pages` and `approvals-pages`.
        These are public clients with PKCE, so no client secret is carried into SOPS by hand.

      Passkeys need HTTPS. As settled 2026-10-01, a private CA in cert-manager signs `bank.example.internal` and
      `id.example.internal`:
      - Each device trusts the root once; the runbook says how.
      - The bank trusts it through an init container that adds it to Java's store, tried in `eclipse-temurin:25-jre`.

      Both home overlays render. `PocketIdSpec` runs Pocket ID on Postgres in Docker and runs the setup script
      against it twice, the second time finding everything in place. Ada is added to `risk` and `auditor`, signs in
      with a one-time login code, and consents for `bank-pages`. The client exchanges the code with its PKCE
      verifier, and the ID token names `iss`, `aud`, `nonce` and `groups: [risk, auditor]`. Its claims carry a
      `jti` and no `sid`, so Approvals records a Pocket ID session by the token's id. One shortcut: Pocket ID's own
      login-code form never submitted under headless Chromium, so the test posts the code from the same browser
      context to the endpoint that form uses. Not shown: passkeys themselves (the browser has no authenticator),
      and anything running on the home cluster.

In `bank-approvals`:

- [x] **`approvals-domain`** — requests, votes, comments and policies as a pure domain: the saga's states,
      separation of duties, counting distinct approvers, supersession, expiry, and auto-approval matched on facts.
      Done when: the domain tests walk every path of the saga above, a requester's own approval, a second from one
      person and a vote after the end are each refused, and facts matching a policy's condition auto-approve while
      facts matching none wait for people.
      Done in bank-approvals (68afe05): 32 domain tests. The same request asked again is a retry, and one asked
      differently under the same id is refused; a vote on content other than what was asked is refused as well, as
      `NotWhatWasAsked`; applied said twice is applied once; the chain names the first event edited, dropped or
      reordered, even one forged with a freshly computed link.
- [x] **`approvals-saga`** — a request as a Lark entity, its events versioned; the API to request, approve, reject,
      comment, withdraw and read; policies from `policies.yaml`; expiry, and asking the owner to apply, by timer.
      Done when: against Postgres, a request approved on one node after the entity moved to another ends applied once,
      with every vote and comment in its history, and one whose owner does not answer is asked again until it does.
      Done in bank-approvals (e395ec7): ten tests against Postgres and the bank's test issuer. Twelve requests voted
      on and commented on through a node that then leaves are approved and applied through the other, each once; a
      silent owner is asked every 300 ms until it says applied, and not after; a newer request supersedes the waiting
      one; a request expires on its own; the refused vote is answered 409 and on the timeline. Until
      `approvals-published` the owner was asked in the log. A node stopping between a new request and its claim on
      the subject leaves the older one live until it ends another way.
- [x] **`approvals-audit`** — refusals as events, who and from where on each, the content hash checked on acting, the
      per-request hash chain, the nightly digest shipped offsite, verification, and the auditor's queries and exports.
      Done when: a test edits one stored event's bytes and `verify` names it; a test changes a request's detail after
      two approvals and the owning service refuses to act; and a person's history lists every vote, refused ones included.
      Done in bank-approvals (caa38c7), four tests against Postgres and Kafka. Bytes edited in one stored event: `GET
      /audit/verify` names that event. A chain rewritten whole with every link recomputed still walks, and the day's
      digest names its head. A change edited after two approvals: the owner sees `ApprovalGiven`'s `content_hash` no
      longer matches what it holds, and says apply-failed; `applied` with the edited hash is refused 409
      (`NotWhatWasApproved`), since `applied` now carries the hash applied. A person's history lists their request,
      comment, approval and both refused votes, each with session, address and user agent. Where it differs from the
      text above:
      - *From where* is the first address in `X-Forwarded-For` as the ingress sets it, and the user agent. The session
        is the token's `sid`, else its `jti`, else when it was issued; which of these Pocket ID's tokens carry is for
        `identity` to confirm.
      - Exports are recorded in a table, `audit_export`, not as journal events: they belong to no request.
      - The auditor's entries are read by walking the journal, not from a read model; a few events per change makes that
        cheap for years.
      - The digest is a table, `approval_digest`, so it reaches offsite with the WAL copy once Approvals is deployed.
        Until then nothing ships it, and the monthly verify beside the restore drill is not yet scheduled.
      - The journal entry changed shape (each event's bytes and its link) under version 1, since no Approvals journal
        exists anywhere yet.
      `approval.proto` gained `origin`, a hash on `ApprovalGiven`, `AutoApproved` and `Applied`, and the service that
      said `Applied` or `ApplyFailed`, all additions that `buf breaking` passes.
- [x] **`approvals-published`** — `bank.approval-events` in `lark-bank-events`.
      Done when: `buf breaking` passes the addition and a consumer reads a request's events in order.
      Done: `approval.proto` beside the bank's own (cases nested, since `transfer.proto` has a `Requested` and a
      `Rejected`), kind and subject on every event; `buf breaking` passes it against the last published image, which
      now records it. In bank-approvals (59e811b) a projection over the journal, spread over the nodes, publishes each
      event in order. Against Kafka and Apicurio, a consumer using Apicurio's deserializer reads a request's seven
      events, sequences 1 to 7, as they happened. The event itself is the owner's first asking; the apply timer asks
      again by sending the `ApprovalGiven` or `AutoApproved` once more under its own sequence, until `applied`. A repeat
      can overtake the events around it only if publishing lags by more than `applyEvery` (30 s).
- [x] **`approvals-pages`** — waiting on me, asked by me, recent, and one request with its diff, impact and timeline.
      Done when: a Playwright test signs in as the requester and two approvers: one comments, the requester answers,
      both approve, and the page shows the diff, the thread and the request applied.
      Done in bank-approvals (be0a9fb). One Playwright test signs in through the test issuer as the requester and two
      approvers, each in a browser context of their own. Ada finds the request waiting on her, sees `1000` struck out and
      `500` put in, and asks a question. The requester finds it among what they asked, answers, and is offered no vote.
      Ada and then Bob approve, the owner applies it, and the page shows it applied, with the diff and all eight events
      of the thread. The test caught two page bugs before they shipped: a `form` style that overrode `hidden`, and a
      vote still offered to someone who had already voted. The lists are drawn from the journal, as the audit's entries
      are.
In `bank-checks`:

- [x] **`checks-oidc`** — the check signs in through Pocket ID, and its own Access (passwords, sessions) goes.
      Done when: the wizard's Playwright test signs in through a test issuer, and the admins Secret is gone.
      Done in bank-checks (c94e2db). Access now signs admins in through the identity provider:
      - The flow is code with PKCE, with a state the browser keeps in a cookie, good once.
      - The ID token is verified with Nimbus, as pelican-oidc verifies the bank's.
      - Whoever the provider puts in `risk` or `admins` (configurable) is an admin. Sessions stay in the check's
        own table.

      The password sign-in, its argon2 hashes, `HashPassword` and `CHECKS_ADMINS` go, and so does the admins Secret
      from `deploy/k8s`, the home overlay and the SOPS template. The wizard's Playwright test:
      - signs in through lark-bank's test issuer, writes a rule, makes it live, signs out and back in;
      - finds someone in `marketing` refused at the callback.

      `checks-docker.sh` signs in through compose's issuer with curl, and the rules it makes live are authored by
      `ada`. At home the check reaches Pocket ID through the home CA, as the bank does. Its wizard is at
      `checks.example.internal`, and only the wizard's paths are routed there: `/screen` stays inside the cluster. Pocket ID
      gains the `checks-pages` client. Not run on the home cluster.
- [x] **`checks-asks`** — the check's first kind: a version proposed, approval requested, made live on `Approved`.
      Done when: in Docker, a rule made live in the wizard waits until two others approve, then declines the next
      transfer it covers; a rejected one never goes live; and a low-severity rule switched off is auto-approved and
      applied at once, with its request on the record.
      Done in bank-checks (afb9af7) and bank-approvals (9ee9fe2); `scripts/asks-docker.sh` runs it, the bank, the
      check and Approvals in compose, twice green.
      - A version made live in the wizard waited on its request. A 900.00 transfer went through. Ada's own approval
        was refused 409, since the check asks on her behalf (`requestedBy`, honoured only for a `services` caller).
        After Bob approved it still waited; after Cy approved, the check heard `ApprovalGiven` on
        `bank.approval-events`, made the version live, declined the next 900.00 transfer, and the request ended
        `applied`.
      - A second version that Bob rejected was refused, never in force: 300.00 still went through under version 1.
      - A low rule switched off was asked with facts `switch-off` and `low`, approved automatically, made off and
        applied within seconds. Its timeline is requested, approved-automatically, applied.

      How it works:
      - The check hashes before, after and facts itself, and makes live only content with that hash. A vector held
        by tests in both repos keeps the two hashes equal.
      - The facts' change is `switch-off`, `describe-only` (the same condition), `tighten` (a first version, or one
        reaching further), `loosen` or `reshape`. Reach is two dry runs over the last seven days.
      - The check gets its token from the identity provider's token endpoint. It is the test grant in compose. At
        home it would be client credentials for a `checks-service` client, which Pocket ID would need to put in
        `services`: not yet tried.
      - Approvals is not deployed anywhere but compose, so approvals stay off in `deploy/k8s` and at home.
      - The wizard shows each version's approval and links its request, but its Playwright test still runs with
        approvals off.

## Acceptance

```bash
./gradlew build
scripts/checks-docker.sh      # with a rule approved by two before it declines anything
```

## Settled

Answered 2026-09-30:

1. **Where does it live?** A service of its own, `bank-approvals`, in its own repository.
2. **Who are the people?** OIDC now: an identity provider at home holds people and groups, and every service signs in
   through it; the check's own Access goes.
3. **How does the owning service hear the answer?** Kafka, `bank.approval-events`, in the published language.
4. **How tamper-evident?** A hash chain per request and a nightly digest shipped offsite, verified on demand and
   monthly beside the restore drill.
5. **Built on?** Kotlin on Lark and Pelican, as the bank is: requests as persistent entities in Lark's journal.
6. **Which identity provider?** Pocket ID.
7. **The request's flow?** A saga of its own: it waits for approvals, then tells the owning service and waits until
   the change is applied, so the record ends with the change in force, not only agreed to.
8. **What an approver sees?** A diff of the subject as it is now and as it would be, as the owning service renders
   both, with the change's impact beside it.
9. **What an approver can do?** Approve (with a comment if they like), reject (a comment required, ending the
   request), and comment without voting, in one thread; an approval may become a rejection until the end.
10. **Changes that need nobody?** Auto-approval conditions in each kind's policy, matched on the facts the owning
    service sends; an auto-approved request is recorded and applied like any other.
11. **Who changes a policy?** A pull request to `policies.yaml` in the approvals repository, reviewed and shipped by
    Flux; each request names the policy version it was decided under.
12. **What a policy requires?** N distinct people from its named groups, never the requester; nothing more until a
    second kind asks for it. Stages in order (one from risk, then one from ops) are the next step when one does.
13. **How long a request waits?** Seven days by default, and a policy may say otherwise; then it expires.
14. **Auto-approval conditions?** Exact matches on the facts the owning service sends. A verdict rule over the facts,
    validated against a facts schema each kind declares, when an exact match is not enough.
15. **How long the audit is kept?** For good, never pruned: it is a few events per change.
16. **Who reads the audit?** Every approver, and an `auditor` group that may read and export but never vote.
17. **"Request changes"?** Not a state of its own: a rejection with its comment, and the requester raises a new
    request, which the old one's thread links to.

All settled 2026-09-30, taking each recommendation.
