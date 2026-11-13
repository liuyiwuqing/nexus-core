-- No foreign key: deletion must survive local message/analysis retention.
CREATE TABLE memory_copies (
    message_id uuid PRIMARY KEY,
    memory_uri text NOT NULL,
    -- Legacy rows lack recorded account/peer provenance and require review.
    memory_identity varchar(256),
    connector_id varchar(64) NOT NULL,
    upstream_event_id varchar(160) NOT NULL,
    content_hash varchar(64),
    state varchar(16) NOT NULL CHECK (state IN ('writing','stored','delete_pending','deleting','deleted')),
    write_until timestamptz,
    write_token uuid,
    lease_until timestamptz,
    lease_token uuid,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    checked_at timestamptz NOT NULL DEFAULT now(),
    reason varchar(64),
    last_error varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX memory_copy_deletions ON memory_copies(next_attempt_at) WHERE state IN ('delete_pending','deleting');
CREATE INDEX memory_copy_revalidation ON memory_copies(checked_at) WHERE state='stored';
INSERT INTO memory_copies(message_id,memory_uri,connector_id,upstream_event_id,content_hash,state)
SELECT a.message_id,a.memory_uri,m.connector_id,m.upstream_event_id,a.content_hash,'stored'
FROM comment_analysis a JOIN inbox_messages m USING(message_id)
WHERE a.memory_uri IS NOT NULL AND m.data_class='public_comment';
