CREATE TABLE tool_jobs (
    job_id uuid PRIMARY KEY,
    actor varchar(64) NOT NULL,
    request_key uuid NOT NULL,
    tool_id varchar(40) NOT NULL,
    risk varchar(16) NOT NULL CHECK (risk IN ('read_only','confirmation')),
    arguments jsonb NOT NULL,
    arguments_hash varchar(64) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('awaiting_confirmation','queued','executing','succeeded','failed','unknown','cancelled','expired')),
    expires_at timestamptz NOT NULL,
    lease_until timestamptz,
    result jsonb,
    last_error varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE(actor,request_key)
);
CREATE INDEX tool_jobs_pending ON tool_jobs(created_at) WHERE state='queued';
CREATE TABLE tool_audit (
    sequence bigserial PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES tool_jobs,
    event varchar(32) NOT NULL,
    detail varchar(64) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);
