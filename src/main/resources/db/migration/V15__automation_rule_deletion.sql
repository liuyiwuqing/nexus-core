-- Keep rule identities, creation keys and executions after a user removes a rule.
ALTER TABLE automation_rules ADD COLUMN deleted_at timestamptz;
ALTER TABLE automation_rules ADD CONSTRAINT deleted_rule_disabled CHECK (deleted_at IS NULL OR NOT enabled);
