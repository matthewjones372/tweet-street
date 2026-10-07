# tweet-street

An example bank made of four services. It is built on [Lark](https://github.com/matthewjones372/lark) (actors,
clustering, event sourcing, streams), [Pelican](https://github.com/matthewjones372/pelican) (HTTP endpoints and
pages), [Proofload](https://github.com/matthewjones372/proofload) (load tests) and
[kimney](https://github.com/matthewjones372/kimney) (mapping between the wire and the domain).

This is a personal project. It exists to try those libraries on something with real moving parts: money that has to
add up, a cluster that loses nodes, and changes that need more than one person to agree. It is not a real bank, it
holds no real money, and the credentials in it are development values for running it on your own machine.

## The folders

| Folder | What it is |
|---|---|
| [`lark-bank/`](lark-bank) | The bank. Accounts, payments and transfers as event-sourced actors on a three-node Lark cluster, journaled to Postgres and published to Kafka. Kotlin. It also holds the docker compose and kind setups that run all four services together. |
| [`bank-checks/`](bank-checks) | Screens each transfer before money moves and flags movements after, by rules an admin writes in a web wizard. Scala 3 and ZIO, with rules in [verdict](https://github.com/matthewjones372/verdict)'s language. |
| [`bank-approvals/`](bank-approvals) | Changes that more than one person agrees to before they take effect, with an audit trail kept as a hash chain. Kotlin on Lark and Pelican. |
| [`bank-access/`](bank-access) | Who may do what, in one place: an OpenFGA model, a service that derives relationships from events, and the client the other services ask. |

Each folder has its own README. [`lark-bank/docs/services.md`](lark-bank/docs/services.md) shows how the four fit
together, and [`lark-bank/specs/`](lark-bank/specs) records why each piece is the way it is.

## Running it locally

You need Docker and JDK 25. Each folder also has a Nix flake whose dev shell has everything else (`nix develop`).

The bank builds against snapshots of Lark and Pelican that are not on Maven Central yet, so clone them beside this
repository and tell Gradle where they are, once, in `~/.gradle/gradle.properties`:

```bash
git clone https://github.com/matthewjones372/lark ../lark
git clone https://github.com/matthewjones372/pelican ../pelican
cat >> ~/.gradle/gradle.properties <<PROPS
larkSource=$(cd ../lark && pwd)
pelicanSource=$(cd ../pelican && pwd)
accessSource=$(pwd)/bank-access
PROPS
```

With docker compose, three bank nodes, two Postgres databases and Kafka on one machine:

```bash
cd lark-bank
./gradlew :app:installDist :loadtest:installDist
docker build -f deploy/docker/bank.Dockerfile -t lark-bank:dev .
docker build -f deploy/docker/loadtest.Dockerfile -t lark-bank-loadtest:dev .
docker compose -f deploy/docker/compose.yml up -d --wait
docker compose -f deploy/docker/compose.yml run --rm -e SCENARIO=transfers load
```

The API and its docs are then at http://localhost:8080/api-docs, the customer pages at http://localhost:8080/ and
Grafana at http://localhost:3000. The compose profiles `checks`, `approvals`, `access`, `traces` and `logs` add the
other services and the observability stack; each needs its service's image built first (see that folder's README).

With [kind](https://kind.sigs.k8s.io), all four services on a local three-worker Kubernetes cluster:

```bash
cd lark-bank
scripts/up.sh       # builds every image from the folders beside it, makes the cluster, applies deploy/k8s
scripts/load.sh transfers
scripts/down.sh
```

`scripts/up.sh` uses Nix for bank-checks and bank-access. `KIND_CONFIG=deploy/kind/one-node.yaml scripts/up.sh`
runs everything on one node for a machine short of disk.

## Building

[`.github/workflows/build.yml`](.github/workflows/build.yml) builds and tests each service on GitHub's runners, the
same way you would by hand: Lark, Pelican and verdict are checked out beside the services, and the bank's event
schemas are published to Maven local before the services that read them are built.

## Licence

Apache License 2.0, in [LICENSE](LICENSE).
