CREATE TABLE pull_connector_state (
    connector_id varchar(24) NOT NULL,
    config_hash char(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 1,
    baseline_at timestamptz,
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error varchar(64),
    last_received integer NOT NULL DEFAULT 0,
    next_poll_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    PRIMARY KEY (connector_id, config_hash)
);
CREATE TABLE pull_connector_seen (
    connector_id varchar(24) NOT NULL,
    config_hash char(64) NOT NULL,
    event_hash char(64) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, config_hash, event_hash),
    FOREIGN KEY (connector_id, config_hash) REFERENCES pull_connector_state
);
