// One function per endpoint the pages use, and nothing the API does not declare (lark-bank spec 0019).
// Every call answers { ok, status, body }: a refusal the API declares is a value to show, not an exception.

async function call(method, path, body) {
  let response;
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? {} : { "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (unreachable) {
    return { ok: false, status: 0, body: { message: "Approvals could not be reached; try again" } };
  }
  // A session that has ended: sign in again, and come back to this page.
  if (response.status === 401) {
    location.href = `/login?return=${encodeURIComponent(location.pathname + location.search)}`;
    return { ok: false, status: 401, body: { message: "Signing in again" } };
  }
  const text = await response.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { message: text }; }
  return { ok: response.ok, status: response.status, body: parsed };
}

const at = (id) => encodeURIComponent(id);

export const me = () => call("GET", "/me");
export const list = (which) => call("GET", `/requests?list=${at(which)}`);
export const request = (id) => call("GET", `/requests/${at(id)}`);
export const approve = (id, hash, comment) => call("POST", `/requests/${at(id)}/approve`, { hash, comment: comment || null });
export const reject = (id, comment) => call("POST", `/requests/${at(id)}/reject`, { comment });
export const comment = (id, text) => call("POST", `/requests/${at(id)}/comment`, { text });
export const withdraw = (id) => call("POST", `/requests/${at(id)}/withdraw`);

export const $ = (selector) => document.querySelector(selector);

/** Shows [text] in [element] as a message of [kind]: good, bad or wait. */
export function show(element, text, kind) {
  element.textContent = text;
  element.dataset.kind = kind;
  element.hidden = false;
}

/** What to show for a result that is not ok: Approvals' own message, which every refusal it declares carries. */
export function refusal(result) {
  return result.body?.message ?? `Approvals answered ${result.status}`;
}

const STATES = {
  "awaiting-approval": "Waiting for approval",
  approved: "Approved, not yet applied",
  applied: "Applied",
  rejected: "Rejected",
  withdrawn: "Withdrawn",
  superseded: "Superseded",
  expired: "Expired",
};

/** A request's state as a small label. */
export function stateLabel(state) {
  const label = document.createElement("span");
  label.className = "state";
  label.dataset.state = state;
  label.textContent = STATES[state] ?? state;
  return label;
}

/** A time as the reader's clock shows it. */
export function when(iso) {
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

// ---- the diff ----

/** The longest common subsequence of [a] and [b], as a walk: each step same, removed (from a) or added (from b). */
function walk(a, b) {
  const rows = a.length + 1;
  const cols = b.length + 1;
  const lengths = Array.from({ length: rows }, () => new Uint32Array(cols));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lengths[i][j] = a[i] === b[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
    }
  }
  const steps = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { steps.push({ kind: "same", a: a[i], b: b[j] }); i++; j++; }
    else if (lengths[i + 1][j] >= lengths[i][j + 1]) steps.push({ kind: "removed", a: a[i++] });
    else steps.push({ kind: "added", b: b[j++] });
  }
  while (i < a.length) steps.push({ kind: "removed", a: a[i++] });
  while (j < b.length) steps.push({ kind: "added", b: b[j++] });
  return steps;
}

/** [text] into [line], with the words [marked] wrapped in [tag]: del on the left, ins on the right. */
function words(line, pairs, side, tag) {
  for (const step of pairs) {
    if (step.kind === "same") line.append(step.a);
    else if ((step.kind === "removed" && side === "a") || (step.kind === "added" && side === "b")) {
      const marked = document.createElement(tag);
      marked.textContent = side === "a" ? step.a : step.b;
      line.append(marked);
    }
  }
}

const tokens = (text) => text.split(/(\s+)/).filter((each) => each !== "");

/**
 * [before] and [after] side by side, in [left] and [right]: lines only one side has are marked, and where one line
 * replaced another, the words that changed within it.
 */
export function diff(before, after, left, right) {
  const steps = walk(before.split("\n"), after.split("\n"));
  const line = (kind) => {
    const span = document.createElement("span");
    span.className = `line ${kind}`;
    return span;
  };
  for (let k = 0; k < steps.length; k++) {
    const step = steps[k];
    const next = steps[k + 1];
    if (step.kind === "removed" && next?.kind === "added") {
      // One line replaced by another: the words that changed, within each.
      const pairs = walk(tokens(step.a), tokens(next.b));
      const was = line("removed");
      words(was, pairs, "a", "del");
      const is = line("added");
      words(is, pairs, "b", "ins");
      left.append(was, "\n");
      right.append(is, "\n");
      k++;
    } else if (step.kind === "same") {
      const was = line("same");
      was.textContent = step.a;
      const is = line("same");
      is.textContent = step.b;
      left.append(was, "\n");
      right.append(is, "\n");
    } else if (step.kind === "removed") {
      const was = line("removed");
      was.textContent = step.a;
      left.append(was, "\n");
    } else {
      const is = line("added");
      is.textContent = step.b;
      right.append(is, "\n");
    }
  }
}

/** The signed-in person in the header, with a way out. */
export async function signedIn() {
  const who = await me();
  if (!who.ok) return null;
  const box = document.createElement("span");
  box.className = "who";
  const name = document.createElement("span");
  name.id = "signed-in";
  name.textContent = who.body.name;
  const out = document.createElement("a");
  out.id = "sign-out";
  out.href = "/logout";
  out.textContent = "Sign out";
  box.append(name, " ", out);
  document.querySelector("header").append(box);
  return who.body;
}
