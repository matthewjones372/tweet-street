// One function per endpoint the pages use, and nothing the API does not declare (bank spec 0006).
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
    // No answer at all is the same advice as a 503: the same reference again is safe.
    return { ok: false, status: 0, body: { message: "The bank could not be reached; try again" } };
  }
  // A session that has ended: sign in again, and come back to this page (bank spec 0021).
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

export const account = (id) => call("GET", `/accounts/${at(id)}`);
export const open = (id, currency, initial) => call("PUT", `/accounts/${at(id)}`, { currency, initial });
export const me = () => call("GET", "/me");
export const myAccounts = () => call("GET", "/accounts");
export const deposit = (id, amount, reference) => call("POST", `/accounts/${at(id)}/deposits`, { amount, reference });
export const withdraw = (id, amount, reference) =>
  call("POST", `/accounts/${at(id)}/withdrawals`, { amount, reference });
export const statement = (id, before) =>
  call("GET", `/accounts/${at(id)}/statement?limit=50${before === undefined ? "" : `&before=${before}`}`);
export const transfer = (id, from, to, amount) => call("PUT", `/transfers/${at(id)}`, { from, to, amount });
export const transferStatus = (id) => call("GET", `/transfers/${at(id)}`);
export const payroll = (id, credits) => call("POST", `/payrolls/${at(id)}`, { credits });
export const looks = (id) => call("GET", `/accounts/${at(id)}/looks`);
export const accountViewers = (id) => call("GET", `/access/accounts/${at(id)}/viewers`);
export const personSees = (subject) => call("GET", `/access/people/${at(subject)}`);
export const actAs = (subject) => call("POST", `/act-as/${at(subject)}`);
export const stopActing = () => call("DELETE", "/act-as");

let listed;
/** The currencies the bank keeps, asked once: each one's code, places, symbol and kind (bank spec 0011). */
export async function currencies() {
  if (!listed) {
    const result = await call("GET", "/currencies");
    listed = result.ok ? result.body : [];
  }
  return listed;
}

/** A fresh reference, made once per movement so that sending it again cannot move the money twice. */
export function reference(prefix) {
  const bytes = crypto.getRandomValues(new Uint8Array(8)); // randomUUID needs a secure context; this does not
  return `${prefix}-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}

/**
 * An amount as the bank writes it, { value: "12.50", currency: "GBP" }, with its currency's symbol: "£12.50". The
 * value is shown as the bank wrote it, never through a float. With `trim`, a crypto amount drops trailing zeros.
 */
export function money(amount, { trim = false } = {}) {
  const currency = listed?.find((each) => each.code === amount.currency);
  let value = amount.value;
  if (trim && currency?.kind === "crypto" && value.includes(".")) value = value.replace(/0+$/, "").replace(/\.$/, "");
  const negative = value.startsWith("-");
  const digits = negative ? value.slice(1) : value;
  return currency ? `${negative ? "-" : ""}${currency.symbol}${digits}` : `${value} ${amount.currency}`;
}

/** What someone typed as an amount, as the plain decimal the bank reads; null if it is not one. The bank checks places. */
export function decimal(text) {
  const trimmed = String(text ?? "").trim();
  return /^\d+(\.\d+)?$/.test(trimmed) ? trimmed : null;
}

/** What to show for a result that is not ok: the bank's own message, which every refusal it declares carries. */
export function refusal(result) {
  return result.body?.message ?? `The bank answered ${result.status}`;
}

/** A 503, or no answer: worth sending again unchanged. Everything else the bank refused means what it says. */
export const retryable = (result) => result.status === 503 || result.status === 0;

export const $ = (selector) => document.querySelector(selector);

export function show(element, text, kind) {
  element.textContent = text;
  element.dataset.kind = kind ?? "";
  element.hidden = !text;
}

let asked;
/** Who the bank takes the caller to be, asked once per page. */
export const whoAmI = () => (asked ??= me());

/**
 * While someone in support acts as a customer (bank spec 0021), every page says so above everything else, with the
 * way back; and when the grant ends the page goes back to them by itself.
 */
function actingBanner(who) {
  const until = new Date(who.actingUntilMillis);
  const banner = document.createElement("p");
  banner.id = "acting";
  banner.setAttribute("role", "status");
  banner.innerHTML = `<span></span> <button id="stop-acting" type="button">Stop</button>`;
  banner.querySelector("span").textContent =
    `${who.actor} (support) is acting as ${who.subject} until ${until.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}. ` +
    "Nothing can be moved or changed.";
  banner.querySelector("#stop-acting").addEventListener("click", async () => {
    await stopActing();
    location.href = "/";
  });
  document.body.prepend(banner);
  setTimeout(() => location.reload(), Math.max(0, who.actingUntilMillis - Date.now()) + 500);
}

/** Who is signed in, and the way out, at the end of every page's header. */
async function signedIn() {
  const header = document.querySelector("header");
  if (!header) return;
  const who = await whoAmI();
  if (!who.ok) return;
  if (who.body.actor && who.body.actingUntilMillis) actingBanner(who.body);
  const acting = who.body.actor ? ` (${who.body.actor} acting)` : "";
  const box = document.createElement("span");
  box.className = "who";
  box.innerHTML = `<span id="signed-in"></span> <a id="sign-out" href="/logout">Sign out</a>`;
  box.querySelector("#signed-in").textContent = who.body.name + acting;
  header.append(box);
}

signedIn();
