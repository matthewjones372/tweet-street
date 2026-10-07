# Working in this repo

bank-access is built the way the rest of the estate is. Its spec is lark-bank's 0022; nothing is built here that
it does not name.

- **The model's tests are its specification.** A relation added or changed comes with the cases that pin it: who it
  lets in, who it keeps out, and what happens when a grant expires. `nix flake check` runs them, as CI does.
- **Nothing is written by hand.** Every relationship comes from an event, an approval or a Pocket ID group, through
  `access-sync`; a fix to the store is a fix to what writes it.
- **Comments say why, in a line or two.** No restating the model, and no history.
- **Docs say what is, not how it got here.** The README and `docs/` describe the current state: what it does, how
  to run it, what it carries now. No history: no earlier runs, fixed gaps, dated logs or PR trails; measurements are
  the latest only. Why belongs in the spec, what changed in the commit. Diagrams are mermaid fences, checked to draw
  before committing. lark-bank's `docs/services.md` describes this service: keep it true.
- **Verify before saying done**, and quote the result.
