CREATE TABLE rule_version (
  rule      TEXT    NOT NULL,
  number    INT     NOT NULL,
  subject   TEXT    NOT NULL,
  document  TEXT    NOT NULL,
  severity  TEXT    NOT NULL,
  position  INT     NOT NULL,
  status    TEXT    NOT NULL,
  author    TEXT    NOT NULL,
  at_millis BIGINT  NOT NULL,
  PRIMARY KEY (rule, number)
);

-- Every node holding the live rules hears of a new version at once; a poll is the backstop.
CREATE FUNCTION version_stored() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('policy_changed', NEW.rule);
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER rule_version_stored AFTER INSERT ON rule_version FOR EACH ROW EXECUTE FUNCTION version_stored();
