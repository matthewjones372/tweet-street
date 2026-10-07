# Working in this repo

bank-approvals is built the way lark-bank is, on Lark and Pelican.

- **Specs come first.** Nothing is built without a committed spec. The brief for this service is lark-bank's spec
  0019, *Changes more than one person agrees to*; its stack entries marked "in bank-approvals" are built here, one per
  pull request. What comes after it is specced in `specs/`, from 0001.
- **A gap is a spec upstream.** When Lark, Pelican or lark-bank's packages lack something, draft the spec there, and
  name it under **Depends on** with the workaround in use until it lands.
- **The domain is pure.** `domain/` depends on Arrow and the JDK, nothing else. Every rule of approval is a test there,
  with no actor, journal or clock: the time is an argument.
- **The record is the point.** Every act is an event, and nothing is edited or deleted. A vote the rules turn away is
  an event too, `VoteRefused`, never a silent no.
- **Errors a caller was promised are values.** `Either` in the ports, and `orFail` on the endpoints. Never
  `runCatching`, and never an `else` on a `when` over a sealed type.
- **Comments say why, in a line or two.** No restating the code, and no history.
- **Docs say what is, not how it got here.** The README and `docs/` describe the current state: what it does, how
  to run it, what it carries now. No history: no earlier runs, fixed gaps, dated logs or PR trails; measurements are
  the latest only. Why belongs in the spec, what changed in the commit. Diagrams are mermaid fences, checked to draw
  before committing. lark-bank's `docs/services.md` describes this service: keep it true.
- **Tests are sentences in backticks**, Kotest matchers, JUnit 5. The failing test comes first.
- **Verify before saying done:** `./gradlew build`, and quote the result. Run it in `nix develop .#ci` as CI does.
