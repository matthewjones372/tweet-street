--liquibase formatted sql

-- The bank's read models, as a Liquibase changelog. Every amount is a decimal at the places of the currency beside
-- it (bank specs 0011 and 0016), and the row keeps those places, so it reads the same whatever the registry says today.

--changeset lark-bank:read-models
-- One row per account: its balance as the statements projection last left it, and the sequence it had reached,
-- which is what makes a replayed event change nothing.
create table account_balance (
    account_id text    primary key,
    owner      text    not null,
    currency   text    not null,
    exponent   int     not null,
    balance    numeric not null,
    last_seq   bigint  not null
);

create table statement_line (
    account_id text    not null,
    seq_nr     bigint  not null,
    kind       text    not null,
    currency   text    not null,
    exponent   int     not null,
    amount     numeric not null,
    balance    numeric not null,
    reference  text    not null,
    at_millis  bigint  not null,
    primary key (account_id, seq_nr)
);

-- The bank's books, a row per currency: what came in from outside, what went out, and what is between two accounts.
-- Currencies never net against each other.
create table ledger_totals (
    currency  text    primary key,
    exponent  int     not null,
    paid_in   numeric not null,
    paid_out  numeric not null,
    in_flight numeric not null,
    events    bigint  not null
);

create table transfer_status (
    transfer_id    text    primary key,
    from_account   text    not null,
    to_account     text    not null,
    currency       text    not null,
    exponent       int     not null,
    amount         numeric not null,
    status         text    not null,
    settled        boolean not null,
    last_seq       bigint  not null,
    updated_millis bigint  not null
);
create index transfer_unsettled on transfer_status (settled, updated_millis);

--changeset lark-bank:access-grants
-- Support's grants (bank spec 0021). Each request for one as the bank read it from Approvals: the grant it asks for,
-- or why the bank refuses it, kept until it is approved or not.
create table access_request (
    request_id   text   primary key,
    content_hash text   not null,
    person       text,
    account_id   text,
    lasts_millis bigint,
    refused      text
);

-- Each grant given, one per approved request, and live until expires_at. Nothing deletes one: an expired grant is
-- what the auditor asks about.
create table access_grant (
    approval_id text        primary key,
    person      text        not null,
    kind        text        not null,
    account_id  text,
    subject     text,
    expires_at  timestamptz not null
);
create index access_grant_held on access_grant (person, account_id, expires_at);

--changeset lark-bank:access-act-as
-- A grant to act as a customer names them, not an account (bank spec 0021's act-as).
alter table access_request add column kind text;
alter table access_request add column subject text;
create index access_grant_acting on access_grant (person, subject, expires_at) where kind = 'act-as';

--changeset lark-bank:access-log
-- Every look by staff, and every refusal (bank spec 0021): who, acting as whom, which endpoint and account, the
-- answer, from where, and the grant used. published_at is set once bank.access-events has it: the outbox.
create table access_log (
    id           bigserial   primary key,
    at           timestamptz not null,
    subject      text        not null,
    name         text        not null,
    groups       text[]      not null,
    actor        text,
    endpoint     text        not null,
    account_id   text,
    status       int         not null,
    address      text,
    user_agent   text,
    grant_id     text,
    published_at timestamptz
);
create index access_log_account on access_log (account_id, id);
create index access_log_unpublished on access_log (id) where published_at is null;

--changeset lark-bank:row-security
-- The database as the second fence (bank spec 0021): what anyone but the tables' owner reads of the read models is
-- what `bank.caller` may see. The bank reads a customer's request as bank_reader with `bank.caller` set for the
-- transaction; its own projections, sweeper and totals read and write as the owner, whom no policy restricts. A query
-- that forgot its `where` returns the caller's rows and no one else's.
alter table account_balance enable row level security;
create policy visible on account_balance for select using (
    owner = current_setting('bank.caller', true)
    or current_setting('bank.auditor', true) = 'on'
    or exists (
        select 1 from access_grant g
        where g.person = current_setting('bank.caller', true) and g.kind = 'view'
          and g.account_id = account_balance.account_id and g.expires_at > now()
    )
);
-- Through account_balance, whose own policy applies inside: a line or a transfer is seen by whoever sees its account.
alter table statement_line enable row level security;
create policy visible on statement_line for select using (
    exists (select 1 from account_balance b where b.account_id = statement_line.account_id)
);
alter table transfer_status enable row level security;
create policy visible on transfer_status for select using (
    exists (select 1 from account_balance b where b.account_id in (transfer_status.from_account, transfer_status.to_account))
);

--changeset lark-bank:read-roles runAlways:true splitStatements:false
-- The readers, made if they are not there and granted what they read, at every start: at home the operator makes them
-- (deploy/k8s/postgres.yaml), perhaps after the first start, and a login there may not create roles. bank_reader is
-- how the bank reads a customer's request; auditor_ro is a person at psql, given a login and password only at home.
do $$
declare
    reader text;
begin
    foreach reader in array array['bank_reader', 'auditor_ro'] loop
        if not exists (select 1 from pg_roles where rolname = reader) then
            begin
                execute format('create role %I nologin', reader);
            exception
                when duplicate_object then null;
                when insufficient_privilege then
                    raise warning '% is missing, and % may not make it: the operator makes it', reader, current_user;
            end;
        end if;
        if exists (select 1 from pg_roles where rolname = reader) then
            execute format('grant select on account_balance, statement_line, transfer_status, access_grant to %I', reader);
        end if;
    end loop;
    if exists (select 1 from pg_roles where rolname = 'bank_reader') and not pg_has_role(current_user, 'bank_reader', 'member') then
        begin
            execute format('grant bank_reader to %I', current_user);
        exception
            when insufficient_privilege then
                raise warning '% is not a member of bank_reader, and may not make itself one', current_user;
        end;
    end if;
end
$$;
