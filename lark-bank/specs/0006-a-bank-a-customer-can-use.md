# 0006 — A bank a customer can use

## Problem

The bank is an HTTP API and its generated docs at `/api-docs`. A demo shows it with curl or with Proofload, so
nobody sees what a customer would: an account, its balance and statement, a transfer that settles, and a refusal
with a reason. The things that make the bank safe, a reference that makes a retry harmless and a transfer that is
pending before it settles, are invisible unless you read the code.

## Not doing

- **No login.** Anyone can open any account id. Identity is a separate spec, if a demo ever needs one.
- **No framework or build step.** HTML and plain JavaScript modules, with no npm, bundler or transpiler.
- **No new domain rules.** The page calls the API as it is; anything it cannot do, it does not do.
- **No mobile design.** A page that works in a browser window.

## Shape

The bank serves the pages beside its API, from `api/src/main/resources/ui/`:

- **`/`** asks for an account id and goes to it, or opens a new one: owner and opening balance.
- **`/accounts/{id}`** shows the balance, and the statement a page at a time: kind, amount, balance after, and
  reference (see open question 4). Deposit and withdraw forms carry a reference the page generates. A 503 offers **Try again**, which sends
  the same reference, so the retry cannot move the money twice.
- **`/transfers/new`** takes from, to and amount, with a transfer id the page generates. After submitting, the
  page polls `GET /transfers/{id}` until the transfer settles: pending, debited, completed, or rejected with its
  reason. Resubmitting sends the same id and shows the same transfer.
- **`/payrolls/new`** takes a pasted `account,amount` list and sends it as one payroll.
- **Every refusal** the API declares (404, 409, 422, 503) shows its own message, next to the field it is about
  where it has one.

```js
// ui/bank.js: one function per endpoint, and nothing the API does not declare
export const withdraw = (id, amount, reference) =>
  call("POST", `/accounts/${id}/withdrawals`, { amount, reference });
```

## Why this shape

Plain HTML and modules keep the page something a reader of the bank can read, with no second toolchain beside
Gradle. The page generates references and transfer ids so that the bank's idempotency can be seen: submit twice
and the money moves once. The alternative is a React app built with Vite. It gives a nicer page, but it doubles the
repository's toolchains for a demo. Recommended: plain.

## Depends on

- **Pelican: pages served beside an API**, Pelican spec 0059 (matthewjones372/pelican#190). The bank builds
  against a local Pelican snapshot, `1.0.1-SNAPSHOT`, until that is released.

## Stack

- [x] **`ui-shell`**: the pages served at `/`, the shared `bank.js` and a stylesheet, and the route beside Pelican's.
      Done when: `GET /` answers the page, `/api-docs` and every endpoint still answer, and a Playwright test opens
      the page in Chromium.
- [x] **`ui-accounts`**: open, look up, balance, the paged statement, deposit and withdraw, and **Try again**.
      Done when: a Playwright test opens an account, withdraws twice with one reference, and sees the money move
      once, and sees the 409 for more than the balance.
- [x] **`ui-transfers`**: a transfer followed until it settles, and a payroll.
      Done when: a Playwright test sees a transfer go from pending to completed, and a transfer to a missing
      account end refunded with its reason.

## Acceptance

```bash
./gradlew build     # the Playwright tests included, on the Chromium already installed
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **Served by the bank, or by a container of its own?** By the bank.
2. **Playwright in the JVM, or Node?** Playwright for Java, in the app's tests, on the Chromium the machine has.
3. **Draft the Pelican spec for static pages now?** Yes: Pelican spec 0059, "Pages served beside an API".
4. **How does the statement page?** 50 newest lines, and a `before` sequence number on the endpoint for the next
   page.

Settled while building:

- **The pages are served by Pelican, at the root.** They were first mounted by hand under `/ui/`, with redirects,
  beside Pelican's route. Pelican spec 0059 now serves them with `pages = pages("ui")`, and an endpoint always wins
  over a page, so they moved to the root: `/` is the index, `/account.html?id=…` an account, `/transfer.html`,
  `/payroll.html` and `/ops` the others. `/accounts/{id}` is still the API's JSON.
- **The handlers dropped `blocking {}`.** On Pelican 0058 a synchronous binder runs on a virtual thread, so
  `handledOrFail` needs no executor of the bank's own.
- **A transfer to a missing account ends Refunded, not Rejected.** The debit is taken first; the credit is
  refused; the refund gives it back. The reason is the credit's refusal. `ui-transfers`' test says so.
- **Money wrote no sign below one unit** ("0.01 is not an amount that can move" for −1). The refusal messages
  the page shows made it visible; the domain is fixed, with a test.
- **The statement is read again while the page is open.** It was read once on load and after a movement the page
  made, so a read model a moment behind the journal left it empty, and a transfer or a deposit made elsewhere never
  showed. The newest page is now read every 2 s while the page is in view, until older lines are loaded. A test
  deposits beside an open page and sees the line arrive. (The first test of a run takes about 12 s, not the page's
  doing: the JVM's first Postgres container, Liquibase run, cluster and Chromium, 3.7, 3.1, 2.5 and 2.2 s.)
