-- A version that would change what is in force waits for approval (lark-bank spec 0019): which request asked about
-- it, the hash of what was asked, and how it ended. Versions stored before this needed nobody.
ALTER TABLE rule_version ADD COLUMN approval TEXT NOT NULL DEFAULT 'NotNeeded';
ALTER TABLE rule_version ADD COLUMN approval_request TEXT;
ALTER TABLE rule_version ADD COLUMN content_hash TEXT;
ALTER TABLE rule_version ADD COLUMN approval_note TEXT;
CREATE UNIQUE INDEX rule_version_approval_request ON rule_version (approval_request);

-- A version given approval changes the live rules as a stored one does.
CREATE TRIGGER rule_version_decided AFTER UPDATE OF approval ON rule_version FOR EACH ROW EXECUTE FUNCTION version_stored();
