-- Admins sign in through the identity provider now (lark-bank spec 0019): sessions opened with a password end here.
DELETE FROM session;

CREATE TABLE sign_in (
  state              TEXT   PRIMARY KEY,
  verifier           TEXT   NOT NULL,
  nonce              TEXT   NOT NULL,
  return_to          TEXT   NOT NULL,
  expires_at_millis  BIGINT NOT NULL
);
