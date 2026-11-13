ALTER TABLE comment_analysis ADD COLUMN memory_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE comment_analysis ADD COLUMN memory_next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE comment_analysis ADD COLUMN memory_lease_until timestamptz;
ALTER TABLE comment_analysis ADD COLUMN memory_lease_token uuid;
