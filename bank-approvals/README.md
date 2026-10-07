# bank-approvals

Changes that more than one person agrees to, before they take effect, with an audit that shows tampering: lark-bank
spec 0019. A service owning something (the check's rules first) asks for approval of one change, shown as the text
before and after; named people approve, reject or comment; the service applies it once enough have agreed, and says so.
Every act is an event in a request's own hash chain, kept for good.

Kotlin on Lark and Pelican, as the bank is. `docs/layout.md` is the repository's shape; `AGENTS.md` how to work here.

```bash
./gradlew build          # in `nix develop .#ci`, as CI runs it
```
