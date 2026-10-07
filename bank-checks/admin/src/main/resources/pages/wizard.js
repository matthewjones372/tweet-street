// The rules wizard (bank spec 0018): every verdict rule can be written here, and nothing else. A draft is a tree of
// groups and conditions, turned into verdict's JSON, which the check validates, describes, tries and dry-runs.

const app = document.getElementById("app");
const who = document.getElementById("who");

const records = { transfer: "block a transfer", movement: "flag a movement" };
const comparisonWords = { gt: ">", gte: "≥", lt: "<", lte: "≤", eq: "is", in: "is one of", isTrue: "is true" };

async function call(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : null;
  if (response.status === 401) throw new SignedOut();
  if (!response.ok) throw new Refused(json);
  return json;
}

class SignedOut extends Error {}
class Refused extends Error {
  constructor(body) {
    super(body?.errors?.join("; ") ?? body?.message ?? "refused");
    this.errors = body?.errors ?? [this.message];
  }
}

function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (value !== undefined && value !== null && value !== false) node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}

function show(...nodes) {
  app.replaceChildren(...nodes);
}

// ---- signing in ----

// Sign-in is the identity provider's (lark-bank spec 0019): off to /login, and back to where this page was.
function signIn() {
  location.href = `/login?return=${encodeURIComponent("/" + location.hash)}`;
}

function signedOutPage() {
  who.replaceChildren();
  show(el("div", { class: "card", id: "signed-out" },
    el("h2", {}, "Signed out"),
    el("div", { class: "actions" }, el("button", { class: "primary", id: "sign-in-button", onclick: signIn }, "Sign in"))));
}

// Who is signed in, and where Approvals' pages are, once known.
let me = null;

// A version's request in Approvals (lark-bank spec 0019), as a link when its pages are known.
function requestLink(version, text) {
  return me?.approvalsPages
    ? el("a", { href: `${me.approvalsPages}/request.html?id=${encodeURIComponent(version.request)}`, class: "request" }, text)
    : el("span", {}, text);
}

async function signedIn() {
  me = await call("GET", "/me");
  who.replaceChildren(el("span", { class: "muted" }, `${me.name} · `),
    el("button", { id: "sign-out", onclick: async () => { await call("POST", "/logout"); signedOutPage(); } }, "Sign out"));
}

// ---- the rules ----

async function rulesPage() {
  const [rules, decisions, flags] = await Promise.all([
    call("GET", "/rules"), call("GET", "/decisions?limit=10"), call("GET", "/flags?limit=10")]);
  show(
    el("section", {},
      el("h2", {}, "Rules"),
      rules.length === 0 ? el("p", { class: "muted" }, "No rules yet.") :
        el("table", {},
          el("tr", {}, el("th", {}, "Rule"), el("th", {}, "Does"), el("th", {}, "Order"), el("th", {}, "Severity"), el("th", {}, "Live")),
          rules.map((rule) => el("tr", {},
            el("td", {}, el("a", { href: `#/rules/${rule.name}` }, rule.name)),
            el("td", {}, records[rule.record]), el("td", {}, rule.position), el("td", {}, rule.severity),
            el("td", {}, rule.live ? `version ${rule.live}` : "off")))),
      el("div", { class: "actions" }, el("button", { class: "primary", id: "new-rule", onclick: () => wizard() }, "New rule"),
        el("a", { class: "button", id: "test-transfer", href: "#/test" }, "Test a transfer"))),
    el("section", {}, el("h2", {}, "Newest decisions"), decisionTable(decisions)),
    el("section", {}, el("h2", {}, "Newest flags"), flagTable(flags)));
}

function decisionTable(decisions) {
  if (decisions.length === 0) return el("p", { class: "muted" }, "None yet.");
  return el("table", {},
    el("tr", {}, el("th", {}, "Transfer"), el("th", {}, "Decision"), el("th", {}, "By")),
    decisions.map((d) => el("tr", {}, el("td", {}, d.transfer),
      el("td", { class: d.outcome === "declined" ? "held" : "clear" }, d.outcome),
      el("td", {}, d.rule ? `${d.rule}, version ${d.version}` : ""))));
}

function flagTable(flags) {
  if (flags.length === 0) return el("p", { class: "muted" }, "None yet.");
  return el("table", {},
    el("tr", {}, el("th", {}, "Movement"), el("th", {}, "Amount"), el("th", {}, "Rule"), el("th", {}, "Severity")),
    flags.map((f) => el("tr", {}, el("td", {}, `${f.account} #${f.sequence}`), el("td", {}, `${f.currency} ${f.amount}`),
      el("td", {}, `${f.rule}, version ${f.version}`), el("td", {}, f.severity))));
}

// Each version side by side, and what each was live for: from it until the next version that was not a draft.
async function rulePage(name) {
  const rule = await call("GET", `/rules/${encodeURIComponent(name)}`);
  const inForce = (v) => v.approval === "not-needed" || v.approval === "given";
  const counted = rule.versions.filter((v) => v.status !== "draft" && inForce(v));
  const liveFor = (version) => {
    if (version.approval === "waiting") return requestLink(version, `waiting for approval to be ${version.status}`);
    if (version.approval === "refused") return requestLink(version, `never in force: its request was ${version.approvalNote}`);
    if (version.status !== "live") return version.status === "off" ? "switched off" : "a draft, never live";
    const next = counted.find((v) => v.number > version.number);
    const from = new Date(version.atMillis).toLocaleString();
    return next ? `${from} to ${new Date(next.atMillis).toLocaleString()}` : `since ${from}`;
  };
  const made = rule.record === "transfer"
    ? decisionTable((await call("GET", "/decisions?limit=200")).filter((d) => d.rule === name))
    : flagTable((await call("GET", "/flags?limit=200")).filter((f) => f.rule === name));
  show(
    el("section", {},
      el("h2", {}, `${name}: ${records[rule.record]}`),
      el("table", { id: "versions" },
        el("tr", {}, el("th", {}, "Version"), el("th", {}, "Rule"), el("th", {}, "Live"), el("th", {}, "By")),
        rule.versions.map((v) => el("tr", {},
          el("td", {}, v.number), el("td", {}, v.words), el("td", {}, liveFor(v)), el("td", {}, v.author)))),
      el("div", { class: "actions" },
        el("button", { class: "primary", onclick: () => wizard(fromVersion(name, rule)) }, "New version"),
        el("button", { onclick: () => switchOff(name, rule) }, "Switch off"))),
    el("section", {}, el("h2", {}, rule.record === "transfer" ? "Its decisions" : "Its flags"), made));
}

async function switchOff(name, rule) {
  const latest = rule.versions[rule.versions.length - 1];
  await call("POST", `/rules/${encodeURIComponent(name)}/versions`,
    { record: rule.record, document: latest.document, severity: latest.severity, position: latest.position, status: "off" });
  rulePage(name);
}

function fromVersion(name, rule) {
  const latest = rule.versions[rule.versions.length - 1];
  return { name, record: rule.record, severity: latest.severity, position: latest.position, tree: toTree(JSON.parse(latest.document)) };
}

// ---- test a transfer: what the check would decide now, across every live rule, stored nowhere ----

async function testPage() {
  const fields = (await call("GET", "/schema/transfer")).fields;
  const defaults = { hourOfDay: String(new Date().getUTCHours()), currency: "GBP" };
  const form = el("div", {}, fields.map((f) => el("div", { class: "row" },
    el("label", { for: `test-${f.name}` }, f.name),
    el("input", { id: `test-${f.name}`, "data-field": f.name, "data-type": f.type, value: defaults[f.name] ?? "",
      inputmode: f.type === "number" ? "decimal" : undefined }))));
  const result = el("div", { id: "test-result" });
  const test = async () => {
    const transfer = {};
    form.querySelectorAll("input").forEach((input) => {
      transfer[input.dataset.field] = input.dataset.type === "number" ? Number(input.value || 0) : input.value;
    });
    const tested = await call("POST", "/screen/test", transfer);
    const verdict = tested.outcome === "declined"
      ? el("p", { class: "held words", id: "test-outcome" }, `Declined by ${tested.rule}, version ${tested.version}.`)
      : el("p", { class: "clear words", id: "test-outcome" }, "Approved: no live rule holds.");
    const rules = tested.rules.length === 0 ? el("p", { class: "muted" }, "No screening rule is live.") :
      el("table", { id: "test-rules" },
        el("tr", {}, el("th", {}, "Order"), el("th", {}, "Rule"), el("th", {}, "Holds"), el("th", {}, "Why")),
        tested.rules.map((r, i) => el("tr", {},
          el("td", {}, i + 1),
          el("td", {}, el("a", { href: `#/rules/${r.rule}` }, `${r.rule}, version ${r.version}`)),
          el("td", { class: r.held ? "held" : "clear" }, r.held ? (r.rule === tested.rule ? "yes: declines" : "yes") : "no"),
          el("td", {}, el("pre", {}, r.evidence)))));
    result.replaceChildren(verdict, rules);
  };
  show(el("section", {},
    el("h2", {}, "Test a transfer"),
    el("p", { class: "muted" },
      "What the check would decide for this transfer now, against every live screening rule in order. Nothing is stored."),
    form,
    el("div", { class: "actions" }, el("button", { class: "primary", id: "test-button", onclick: test }, "Test it"),
      el("a", { href: "#/" }, "Back to the rules")),
    result));
}

// ---- the draft: a tree of groups and conditions, and verdict's JSON ----

const condition = () => ({ kind: "condition", field: "", comparison: "", value: "" });
const group = (mode = "all") => ({ kind: "group", mode, negated: false, children: [condition()] });

function toRule(node) {
  if (node.kind === "condition") {
    const { field: path, comparison: op, value } = node;
    let rule;
    if (op === "isTrue") rule = { op, path };
    else if (op === "eq") rule = { op, path, value };
    else if (op === "in") rule = { op, path, values: value.split(",").map((v) => v.trim()).filter(Boolean) };
    else rule = { op, path, value: Number(value) };
    return rule;
  }
  const parts = node.children.map(toRule);
  if (parts.length === 0) throw new Refused({ errors: ["A group has no conditions: add one, or remove the group."] });
  const joined = parts.reduce((left, right) => ({ op: node.mode === "all" ? "and" : "or", left, right }));
  return node.negated ? { op: "not", rule: joined } : joined;
}

// A stored rule back into groups, so a new version starts from the last.
function toTree(rule) {
  if (rule.op === "not") {
    const inner = toTree(rule.rule);
    if (inner.kind === "group" && !inner.negated) return { ...inner, negated: true };
    return { kind: "group", mode: "all", negated: true, children: [inner] };
  }
  if (rule.op === "and" || rule.op === "or") {
    const mode = rule.op === "and" ? "all" : "any";
    const flatten = (r) => (r.op === rule.op ? [...flatten(r.left), ...flatten(r.right)] : [toTree(r)]);
    return { kind: "group", mode, negated: false, children: flatten(rule) };
  }
  const value = rule.op === "in" ? rule.values.join(", ") : rule.value ?? "";
  const leaf = { kind: "condition", field: rule.path, comparison: rule.op, value: String(value) };
  return { kind: "group", mode: "all", negated: false, children: [leaf] };
}

// ---- the wizard ----

const stepNames = ["What it is about", "The conditions", "Read it back", "Try it", "Make it live"];

async function wizard(start) {
  const draft = start ?? { name: "", record: "transfer", severity: "high", position: 1, tree: group() };
  let step = start ? 1 : 0;
  const schemas = {};
  const fieldsOf = async (record) => (schemas[record] ??= (await call("GET", `/schema/${record}`)).fields);
  const document = () => JSON.stringify(toRule(draft.tree));

  async function render() {
    const steps = el("div", { class: "steps" }, stepNames.map((name, i) => el("span", { class: i === step ? "now" : "" }, `${i + 1}. ${name}`)));
    const body = await [about, conditions, readBack, tryIt, makeLive][step]();
    show(steps, body);
  }
  const next = () => { step += 1; render(); };
  const back = () => { step -= 1; render(); };
  const nav = (...extra) => el("div", { class: "actions" },
    step > 0 ? el("button", { type: "button", onclick: back }, "Back") : "", ...extra);

  function about() {
    return el("section", {},
      el("h2", {}, stepNames[0]),
      el("label", { for: "rule-name" }, "Name: lower case, digits and dashes"),
      el("input", { id: "rule-name", value: draft.name, disabled: !!start, oninput: (e) => (draft.name = e.target.value) }),
      el("label", { for: "rule-does" }, "It will"),
      el("select", { id: "rule-does", disabled: !!start,
        onchange: (e) => { draft.record = e.target.value; draft.tree = group(); } },
        Object.entries(records).map(([record, words]) => el("option", { value: record, selected: record === draft.record }, words))),
      el("label", { for: "rule-severity" }, "Severity"),
      el("select", { id: "rule-severity", onchange: (e) => (draft.severity = e.target.value) },
        ["low", "medium", "high"].map((s) => el("option", { value: s, selected: s === draft.severity }, s))),
      el("label", { for: "rule-position" }, "Order: of the rules that hold, the first declines"),
      el("input", { id: "rule-position", type: "number", min: 1, value: draft.position,
        oninput: (e) => (draft.position = Number(e.target.value)) }),
      nav(el("button", { class: "primary", id: "next", onclick: next }, "Next")));
  }

  async function conditions() {
    const fields = await fieldsOf(draft.record);
    const editor = el("div", { id: "conditions" });
    const redraw = () => editor.replaceChildren(groupEditor(draft.tree, fields, redraw, "g"));
    redraw();
    return el("section", {}, el("h2", {}, stepNames[1]), editor,
      nav(el("button", { class: "primary", id: "next", onclick: next }, "Next")));
  }

  async function readBack() {
    let checked;
    try {
      checked = await call("POST", "/rules/validate", { record: draft.record, document: document() });
    } catch (refused) {
      if (!(refused instanceof Refused)) throw refused;
      checked = { errors: refused.errors };
    }
    const ok = checked.errors.length === 0;
    return el("section", {},
      el("h2", {}, stepNames[2]),
      ok ? el("p", { class: "words", id: "words" }, checked.words) :
        el("ul", { class: "errors", id: "errors" }, checked.errors.map((e) => el("li", {}, e))),
      checked.simplest ? el("p", { class: "muted", id: "simplest" }, `Or, more simply: ${checked.simplest}`) : "",
      nav(ok ? el("button", { class: "primary", id: "next", onclick: next }, "Next") : ""));
  }

  async function tryIt() {
    const fields = await fieldsOf(draft.record);
    const record = el("div", {}, fields.map((f) => el("div", { class: "row" },
      el("label", { for: `try-${f.name}` }, f.name),
      el("input", { id: `try-${f.name}`, "data-field": f.name, "data-type": f.type }))));
    const result = el("div", { id: "tried" });
    const dry = el("div", { id: "dry-run" });
    const tryOne = async () => {
      const values = {};
      record.querySelectorAll("input").forEach((input) => {
        values[input.dataset.field] = input.dataset.type === "number" ? Number(input.value || 0) : input.value;
      });
      try {
        const tried = await call("POST", "/rules/try", { record: draft.record, document: document(), [draft.record]: values });
        result.replaceChildren(el("p", { class: tried.held ? "held" : "clear" }, tried.held ? "It holds." : "It does not hold."),
          el("pre", {}, tried.evidence));
      } catch (refused) {
        result.replaceChildren(el("p", { class: "errors" }, refused.message));
      }
    };
    const dryRun = async () => {
      const run = await call("POST", "/rules/dry-run", { record: draft.record, document: document() });
      draft.share = run.share;
      const verb = draft.record === "transfer" ? "declined" : "flagged";
      dry.replaceChildren(el("p", {}, `Over the last seven days it would have ${verb} ${run.matched} of ${run.of}.`),
        run.examples.length ? el("p", { class: "muted" }, `For example: ${run.examples.join(", ")}`) : "");
    };
    return el("section", {},
      el("h2", {}, stepNames[3]),
      el("p", { class: "muted" }, `A ${draft.record} to try it on:`), record,
      el("div", { class: "actions" }, el("button", { id: "try", onclick: tryOne }, "Try it"),
        el("button", { id: "dry-run-button", onclick: dryRun }, "Dry run over the last seven days")),
      result, dry,
      nav(el("button", { class: "primary", id: "next", onclick: next }, "Next")));
  }

  function makeLive() {
    const outcome = el("div", { id: "stored" });
    const store = (status) => async () => {
      try {
        const version = await call("POST", `/rules/${encodeURIComponent(draft.name)}/versions`,
          { record: draft.record, document: document(), severity: draft.severity, position: draft.position, status });
        const said = version.approval === "waiting"
          ? el("p", {}, `Stored as version ${version.number}, ${version.status} once approved. `,
              requestLink(version, "See the request"))
          : el("p", {}, `Stored as version ${version.number}, ${version.status}.`);
        outcome.replaceChildren(said, el("a", { href: `#/rules/${draft.name}` }, "See the rule"));
      } catch (refused) {
        outcome.replaceChildren(el("p", { class: "errors" }, refused.message));
      }
    };
    const share = draft.share === undefined ? "" :
      el("p", {}, `It would have ${draft.record === "transfer" ? "declined" : "flagged"} ${(draft.share * 100).toFixed(1)}% of the last seven days'.`);
    return el("section", {},
      el("h2", {}, stepNames[4]), share,
      el("div", { class: "actions" },
        el("button", { class: "primary", id: "make-live", onclick: store("live") }, "Make it live"),
        el("button", { id: "save-draft", onclick: store("draft") }, "Save as a draft")),
      outcome, nav());
  }

  await render();
}

function groupEditor(node, fields, redraw, id) {
  const children = node.children.map((child, i) => {
    const childId = `${id}-${i}`;
    const remove = el("button", { type: "button", "aria-label": "Remove", onclick: () => { node.children.splice(i, 1); redraw(); } }, "✕");
    return child.kind === "group"
      ? el("div", {}, groupEditor(child, fields, redraw, childId), el("div", { class: "actions" }, remove))
      : conditionEditor(child, fields, redraw, childId, remove);
  });
  // A group is all of or any of its rows, or the negation of either: none of, not all of.
  const modes = { all: ["all", false], any: ["any", false], none: ["any", true], notAll: ["all", true] };
  const current = Object.keys(modes).find((m) => modes[m][0] === node.mode && modes[m][1] === node.negated);
  return el("div", { class: `group${node.negated ? " negated" : ""}`, id },
    el("div", { class: "row" },
      el("select", { id: `${id}-mode`, "aria-label": "how this group's rows combine",
        onchange: (e) => { [node.mode, node.negated] = modes[e.target.value]; redraw(); } },
        el("option", { value: "all", selected: current === "all" }, "all of these"),
        el("option", { value: "any", selected: current === "any" }, "any of these"),
        el("option", { value: "none", selected: current === "none" }, "none of these"),
        el("option", { value: "notAll", selected: current === "notAll" }, "not all of these"))),
    children,
    el("div", { class: "actions" },
      el("button", { type: "button", id: `${id}-add-condition`, onclick: () => { node.children.push(condition()); redraw(); } }, "Add a condition"),
      el("button", { type: "button", id: `${id}-add-group`, onclick: () => { node.children.push(group("any")); redraw(); } }, "Add a group")));
}

function conditionEditor(node, fields, redraw, id, remove) {
  const field = fields.find((f) => f.name === node.field);
  return el("div", { class: "row", id },
    el("select", { id: `${id}-field`, "aria-label": "field",
      onchange: (e) => { node.field = e.target.value; node.comparison = ""; node.value = ""; redraw(); } },
      el("option", { value: "" }, "a field…"),
      fields.map((f) => el("option", { value: f.name, selected: f.name === node.field }, f.name))),
    el("select", { id: `${id}-comparison`, "aria-label": "comparison", disabled: !field,
      onchange: (e) => { node.comparison = e.target.value; redraw(); } },
      el("option", { value: "" }, "compared…"),
      (field?.comparisons ?? []).map((c) => el("option", { value: c, selected: c === node.comparison }, comparisonWords[c]))),
    node.comparison === "isTrue" ? "" :
      el("input", { id: `${id}-value`, "aria-label": "value", value: node.value,
        placeholder: node.comparison === "in" ? "one, another" : "", oninput: (e) => (node.value = e.target.value) }),
    remove);
}

// ---- routing ----

async function route() {
  try {
    await signedIn();
    const rule = location.hash.match(/^#\/rules\/(.+)$/);
    if (rule) await rulePage(decodeURIComponent(rule[1]));
    else if (location.hash === "#/test") await testPage();
    else await rulesPage();
  } catch (error) {
    if (error instanceof SignedOut) signIn();
    else show(el("section", {}, el("p", { class: "errors" }, error.message)));
  }
}

window.addEventListener("hashchange", route);
route();
