CREATE TABLE session (
  token              TEXT   PRIMARY KEY,
  admin              TEXT   NOT NULL,
  expires_at_millis  BIGINT NOT NULL
);
