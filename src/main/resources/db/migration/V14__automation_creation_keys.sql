-- Existing/unkeyed callers keep independent creation. A keyed replay must
-- compare the immutable original request, even after the rule is enabled/edited.
ALTER TABLE automation_rules ADD COLUMN create_request_key uuid UNIQUE;
ALTER TABLE automation_rules ADD COLUMN create_request_hash varchar(64);
ALTER TABLE automation_rules ADD CONSTRAINT automation_creation_key_pair
    CHECK ((create_request_key IS NULL) = (create_request_hash IS NULL));
