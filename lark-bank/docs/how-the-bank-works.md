# How the bank works

Seven pictures of Lark Bank. Each shows one idea, and names the spec that decided it.

1. [The whole system](#1-the-whole-system)
2. [One account, one writer](#2-one-account-one-writer)
3. [A transfer, step by step](#3-a-transfer-step-by-step)
4. [The saga's states](#4-the-sagas-states)
5. [A retry that moves money once](#5-a-retry-that-moves-money-once)
6. [The read side: journal to ledger and Kafka](#6-the-read-side-journal-to-ledger-and-kafka)
7. [From a domain event to bytes](#7-from-a-domain-event-to-bytes)

## 1. The whole system

Three nodes form one Lark cluster. Every account and every transfer is an entity that lives on exactly one node,
wherever the cluster placed its shard. Any node takes any HTTP call and forwards it to the owner. Events go to a
journal split across two Postgres databases. The read models, spread over the nodes, also publish every event to
Kafka, for other services to build on (spec 0015); the bank itself reads nothing back.

```mermaid
flowchart LR
    client([Browser, curl, Proofload]) -->|HTTP| lb{{Service / any node}}

    subgraph cluster[Lark cluster: SWIM membership, 256 shards per kind]
        direction TB
        subgraph n1[bank-1]
            h1[Pelican HTTP] --> s1[sharding regions]
            s1 --> a1[(account and transfer<br/>entities it owns)]
            rm[read-model workers<br/>and the sweeper]
        end
        subgraph n2[bank-2]
            h2[Pelican HTTP] --> s2[sharding regions]
            s2 --> a2[(entities)]
        end
        subgraph n3[bank-3]
            h3[Pelican HTTP] --> s3[sharding regions]
            s3 --> a3[(entities)]
        end
        s1 <-.->|asks and tells,<br/>Protobuf| s2
        s2 <-.-> s3
        s1 <-.-> s3
    end

    lb --> h1 & h2 & h3

    subgraph journal[Journal split by account id, spec 0004]
        db0[(Postgres db-0<br/>journal + read models + offsets)]
        db1[(Postgres db-1<br/>journal)]
    end

    a1 & a2 & a3 -->|append events| db0 & db1
    rm -->|follow each database's feed| db0 & db1
    rm -->|statements, balances,<br/>ledger totals, transfer status| db0
    rm -->|publish every event,<br/>Protobuf| kafka[(Kafka<br/>bank.account-events<br/>bank.transfer-events)]
    rm -.->|schemas at start| apicurio[(Apicurio)]
    kafka -->|lark-bank-events| others([other services])
```

Membership is SWIM, joined as `bank.cluster` says through lark-app-cluster (lark spec 0096): static seeds in
Docker, or the pods API on Kubernetes, where `CLUSTER_JOIN=kubernetes`. A split is settled by the majority side in
Docker, and by a `Lease` on Kubernetes (spec 0003). A node the others downed ends its process, and is started again.

## 2. One account, one writer

An account is a persistent, sharded entity. Only it changes its balance. It decides each command with the pure
domain and writes the events before it answers. The commands waiting in its mailbox are decided together and
written in one append, up to 64 (lark spec 0086), so a busy account is not held back by a commit per payment.

An account pays out at most its daily limit in a UTC day, withdrawals and transfers' debits together: 10,000 in its
currency, or the limit it was opened with (spec 0027). A refund on the same day gives its debit's share back. A
payout past the limit is refused with the limit and what is left today: a 409 tagged `daily_limit_exceeded` for a
withdrawal, and a transfer `Rejected` with that reason.

```mermaid
sequenceDiagram
    autonumber
    participant C as Callers
    participant A as Account acc-7 (its one node)
    participant J as Journal (db-0 or db-1, by acc-7's slice)

    C->>A: Withdraw 30, ref w-1
    C->>A: Deposit 10, ref d-9
    C->>A: Withdraw 500, ref w-2
    Note over A: all three waiting: one batch
    A->>A: decide(Withdraw 30) → Withdrawn
    A->>A: decide(Deposit 10) → Deposited
    A->>A: decide(Withdraw 500) → InsufficientFunds (a refusal, written nowhere)
    A->>J: one append: [Withdrawn, Deposited], expected sequence n
    J-->>A: written: n+2
    A-->>C: balance after each, and 409 for w-2
    Note over A,J: every 100 events a snapshot, and events the older snapshot covers are pruned<br/>once every read model of that database has read them
```

## 3. A transfer, step by step

A transfer is a saga: an entity of its own that moves money between two accounts, which may live on two other
nodes. Each step is an event, so a saga recovered on another node carries on from its last event. Each leg is
idempotent by transfer id.

```mermaid
sequenceDiagram
    autonumber
    participant U as HTTP caller
    participant T as Transfer t-1 (saga)
    participant F as Account from
    participant D as Account to

    U->>T: PUT /transfers/t-1 {from, to, 250}
    T->>T: persist Requested → Pending
    T->>F: Debit(t-1, 250)
    Note right of T: a leg timer: no answer in 2 s, ask again
    alt from can pay
        F-->>T: balance
        T->>T: persist SourceDebited → Debited
        T->>D: Credit(t-1, 250)
        alt to is open
            D-->>T: balance
            T->>T: persist DestinationCredited → Completed
        else to refuses
            D-->>T: NoSuchAccount
            T->>T: persist CreditRefused → Refunding
            T->>F: Refund(t-1, 250)
            F-->>T: balance
            T->>T: persist SourceRefunded → Refunded
        end
    else from cannot pay
        F-->>T: InsufficientFunds
        T->>T: persist Rejected → Rejected
    end
    T-->>U: the settled transfer, or Pending if it took longer than 1 s
```

A saga whose node dies mid-transfer starts again elsewhere when it is next asked. If nobody asks, the sweeper
nudges any saga that has not moved for 5 s, found through the transfer-status read model.

## 4. The saga's states

The next step is read off the state, never remembered separately. That is what makes a restart safe.

```mermaid
stateDiagram-v2
    [*] --> Pending: Requested
    Pending --> Debited: SourceDebited<br/>(debit accepted)
    Pending --> Rejected: Rejected<br/>(debit refused)
    Debited --> Completed: DestinationCredited<br/>(credit accepted)
    Debited --> Refunding: CreditRefused<br/>(credit refused)
    Refunding --> Refunded: SourceRefunded
    Refunding --> Refunding: refund refused:<br/>stay and try again
    Completed --> [*]
    Rejected --> [*]
    Refunded --> [*]

    note right of Pending : the next step debits the source
    note right of Debited : the next step credits the destination
    note right of Refunding : the next step refunds the source
```

The ledger checks this at every moment: the sum of balances plus the money in flight equals paid in less paid out.
Money is in flight between `Debited` and `Completed` or `Refunded`.

## 5. A retry that moves money once

A call that times out is `Unavailable`, a 503 that says to try again with the same reference. The account keeps the
last 256 references it applied, so the retry is recognised and answered, not applied again.

```mermaid
sequenceDiagram
    autonumber
    participant U as Caller
    participant N as Any node
    participant A as Account acc-7

    U->>N: POST withdrawals {30, ref w-1}
    N->>A: Withdraw 30, w-1
    A->>A: persist Withdrawn (w-1 now recent)
    A--xN: the answer is lost: a node moving, a slow journal
    N-->>U: 503 Unavailable: try again with the same reference
    U->>N: POST withdrawals {30, ref w-1}
    N->>A: Withdraw 30, w-1
    A->>A: w-1 is recent: no event
    A-->>N: the balance, unchanged by the retry
    N-->>U: 200: moved once
```

Payroll credits take a different path to the same guarantee. They go through a reliable producer, which numbers
each credit per account and resends until it is confirmed. The account keeps the last number it handled from each
producer, in the same append as the events.

## 6. The read side: journal to ledger and Kafka

Each journal database has its own feed, in its own order. Each database's feed is split into partitions by the
ids' slices (spec 0014, lark spec 0106), four by default, and each partition of each read model saves its own offset,
such as `statements@db-1#2`. A worker per database and partition runs its statements, transfer status and Kafka
publishing; the cluster spreads the workers over the nodes as evenly as they go. The projections write in batches,
one transaction and one saved offset per batch, and a replayed batch changes nothing: the ledger's totals count only
the statement lines a batch inserted, whichever partition got there first.

```mermaid
flowchart LR
    subgraph db0[db-0]
        f0[journal feed<br/>up to its watermark]
    end
    subgraph db1[db-1]
        f1[journal feed<br/>up to its watermark]
    end

    subgraph workers["read-model workers: databases × partitions, spread over the nodes"]
        w00["db-0 #0: statements,<br/>transfers, kafka"]
        w03["db-0 #1..#3"]
        w10["db-1 #0"]
        w13["db-1 #1..#3"]
    end
    subgraph singleton[singleton, on one node]
        sw[sweeper]
    end

    f0 -->|its slices| w00 & w03
    f1 -->|its slices| w10 & w13

    w00 & w03 & w10 & w13 --> rm[(statement lines,<br/>account balances,<br/>ledger totals)]
    w00 & w03 & w10 & w13 --> ts[(transfer status)]
    ts --> sw -->|Nudge a stuck saga| saga([transfer entity])
    w00 & w03 & w10 & w13 -->|Publisher: offset saved<br/>only once Kafka acks| kafka[(Kafka: account<br/>and transfer events)]

    rm --> ledger[/GET /ledger: conserved?/]
    ts --> status[/GET /transfers/]
```

If a worker's node leaves, the cluster starts the worker on another, and each of its projections resumes from its
saved offset. If the sweeper's node leaves, the singleton moves in the same way.

## 7. From a domain event to bytes

The domain knows nothing of serialisation. Wire shapes in `bank.protocol.wire` mirror it case for case. kimney
derives the mapping both ways at compile time, and a Lark `Kotlinx.oneOf` table writes each sealed shape under fixed
tags. `schema/bank.proto` is generated from the wire shapes and pinned (spec 0005).

```mermaid
flowchart LR
    d[domain<br/>AccountEvent.Withdrawn<br/>amount: Money] -->|"toWire(): transformInto()<br/>kimney, compile time"| w[wire<br/>AccountEvent.Withdrawn<br/>amount: Long]
    w -->|"Kotlinx.oneOf, tag 3"| b[bytes: field 3 = the message<br/>a protobuf oneof]
    b -->|journal row, node to node| b2[bytes]
    b2 -->|"read, by tag"| w2[wire shape]
    w2 -->|"toDomain(): transformInto()"| d2[domain event]

    w -.->|"Kotlinx.proto()"| p[[schema/bank.proto<br/>pinned, and checked by protoc]]
    p -.->|protoc| other([a reader in any language])
```

A field or a case added to the domain and not to its wire shape stops the build at the `transformInto()` that
cannot fill it.

What Kafka carries is not these bytes but a contract of its own (spec 0015): `events/`'s `.proto` files, published as
`lark-bank-events`, mapped from the domain in `protocol`'s `Contract.kt`, and only ever added to. The journal's
encoding can change with the bank; the contract changes only by growing, checked by `buf breaking` in the build and
by Apicurio's `FULL_TRANSITIVE` rule at run time.
