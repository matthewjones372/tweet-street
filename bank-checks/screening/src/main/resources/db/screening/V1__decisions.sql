-- One decision per transfer, ever; kept with what was asked, so a dry run can read the last seven days.
CREATE TABLE decision (
  transfer            TEXT    PRIMARY KEY,
  outcome             TEXT    NOT NULL,
  rule                TEXT,
  version             INT,
  evidence            TEXT    NOT NULL,
  from_account        TEXT    NOT NULL,
  to_account          TEXT    NOT NULL,
  currency            TEXT    NOT NULL,
  amount              NUMERIC NOT NULL,
  hour_of_day         INT     NOT NULL,
  requested_at_millis BIGINT  NOT NULL,
  decided_at_millis   BIGINT  NOT NULL
);

CREATE INDEX decision_decided_at ON decision (decided_at_millis);
CREATE INDEX decision_rule ON decision (rule, decided_at_millis);
