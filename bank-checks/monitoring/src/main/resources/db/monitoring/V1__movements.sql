-- Each account event read, once, with the movement it was: a dry run reads the last seven days of movements.
CREATE TABLE movement (
  account     TEXT    NOT NULL,
  sequence    BIGINT  NOT NULL,
  kind        TEXT,
  currency    TEXT,
  amount      NUMERIC,
  reference   TEXT,
  hour_of_day INT,
  at_millis   BIGINT  NOT NULL,
  PRIMARY KEY (account, sequence)
);

CREATE INDEX movement_at ON movement (at_millis);

CREATE TABLE flag (
  account   TEXT   NOT NULL,
  sequence  BIGINT NOT NULL,
  rule      TEXT   NOT NULL,
  version   INT    NOT NULL,
  severity  TEXT   NOT NULL,
  at_millis BIGINT NOT NULL,
  currency  TEXT   NOT NULL,
  amount    NUMERIC NOT NULL,
  evidence  TEXT   NOT NULL,
  PRIMARY KEY (account, sequence, rule, version)
);

CREATE INDEX flag_rule ON flag (rule, at_millis);
