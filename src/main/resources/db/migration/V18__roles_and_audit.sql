CREATE TABLE role_tokens (
    token_id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('operator','viewer')),
    token_hash varchar(64) NOT NULL UNIQUE,
    version integer NOT NULL DEFAULT 1,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE access_audit (
    audit_id uuid PRIMARY KEY,
    actor varchar(80) NOT NULL DEFAULT 'anonymous',
    role varchar(16) NOT NULL DEFAULT 'anonymous',
    method varchar(8) NOT NULL,
    target varchar(256) NOT NULL,
    status integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX access_audit_recent ON access_audit(created_at DESC);
