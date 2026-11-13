CREATE TABLE comment_analysis (
    message_id uuid PRIMARY KEY REFERENCES inbox_messages,
    status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','succeeded','failed','blocked')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    lease_token uuid,
    last_error varchar(64),
    summary text,
    category varchar(16),
    importance varchar(12),
    has_question boolean,
    reply_draft text,
    content_hash varchar(64),
    analyzed_at timestamptz,
    memory_status varchar(16) NOT NULL DEFAULT 'off' CHECK (memory_status IN ('off','pending','stored','error','blocked')),
    memory_uri text,
    memory_error varchar(64),
    memory_at timestamptz
);
CREATE INDEX comment_analysis_due ON comment_analysis(next_attempt_at) WHERE status IN ('pending','processing');
