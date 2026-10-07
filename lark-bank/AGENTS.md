# Working in this repo

The bank is built the way Lark and Pelican are.

- **Specs come first.** Nothing is built without a committed spec in `specs/`; see `specs/README.md`.
- **A gap is a spec upstream.** When Lark or Pelican lacks something the bank needs, draft the spec in that
  repository, and name it under the bank spec's **Depends on** with the workaround in use until it lands.
- **The domain is pure.** `domain/` depends on Arrow and nothing else. Every rule about money is a test there,
  with no actor, journal or clock.
- **Errors a caller was promised are values.** `Either` in the ports, and `orFail` on the endpoints. Never
  `runCatching`, and never an `else` on a `when` over a sealed type.
- **Comments say why, in a line or two.** No restating the code, and no history.
- **Docs say what is, not how it got here.** READMEs and `docs/` describe the current state: what each part does,
  how to run it, what it carries now. No history: no "earlier runs", "what building it found", fixed gaps, dated
  run logs or PR trails. When something changes, rewrite the sentence; measurements are the latest only. Why a
  thing is so belongs in its spec, what changed in the commit.
- **Diagrams are mermaid,** in a fence in the doc, so GitHub draws them. Check one draws before committing it
  (`mmdc`, from `@mermaid-js/mermaid-cli`). [The services](docs/services.md) is the overview of the estate; keep it
  true when a service changes.
- **Tests are sentences in backticks**, Kotest matchers, JUnit 5. The failing test comes first.
- **Verify before saying done:** `./gradlew build`, and quote the result. Run it in `nix develop .#ci`, which has the
  JDKs, the Chromium the page tests drive, and the buf and protoc the events' schemas need, as CI runs it.
