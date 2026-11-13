ALTER TABLE inbox_messages ADD COLUMN content_purged_at timestamptz;
CREATE INDEX inbox_unpurged ON inbox_messages(received_at) WHERE content_purged_at IS NULL;
CREATE TABLE retention_audit (
    sequence bigserial PRIMARY KEY,
    actor varchar(24) NOT NULL,
    bodies_purged integer NOT NULL,
    messages_deleted integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
