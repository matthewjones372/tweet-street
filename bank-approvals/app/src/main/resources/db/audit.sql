--liquibase formatted sql

--changeset approvals:audit
-- Every night, the head of every request's chain changed that day, hashed into one digest (lark-bank spec 0019). In
-- this database, so the backups' WAL copy carries it offsite; a journal rewritten with its links recomputed no longer
-- matches the heads recorded here.
create table approval_digest (
    day     date        not null,
    digest  text        not null,
    heads   integer     not null,
    made_at timestamptz not null,
    primary key (day)
);

create table approval_digest_head (
    day        date   not null references approval_digest (day),
    request_id text   not null,
    sequence   bigint not null,
    link       text   not null,
    primary key (day, request_id)
);

-- Each export of the audit: who, from where, what they asked for, and how much they took.
create table audit_export (
    at         timestamptz not null,
    subject    text        not null,
    name       text        not null,
    session    text        not null,
    address    text        not null,
    user_agent text        not null,
    filter     text        not null,
    format     text        not null,
    rows       integer     not null
);
create index audit_export_at on audit_export (at);
