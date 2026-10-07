# bank-checks

The checks outside [lark-bank](../lark-bank), as its spec 0018 describes them:
**screening**, which the bank asks about each transfer before its source is debited (`POST /screen`), and
**monitoring**, which reads every account event the bank publishes and flags the movements its rules question. An
admin writes the rules in a wizard the service serves, in [verdict](https://github.com/matthewjones372/verdict)'s
rule language.

```bash
nix develop -c sbt compile test scalafmtCheckAll
```

See [AGENTS.md](AGENTS.md) for the contexts, the language and the layers.
