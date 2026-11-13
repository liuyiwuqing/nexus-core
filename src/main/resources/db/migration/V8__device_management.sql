CREATE TABLE device_telemetry (
    device_id varchar(40) PRIMARY KEY REFERENCES devices,
    body jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE device_config_requests (
    device_id varchar(40) NOT NULL REFERENCES devices,
    request_id varchar(40) NOT NULL,
    expected_revision bigint NOT NULL CHECK(expected_revision BETWEEN 0 AND 4294967294),
    config jsonb NOT NULL,
    envelope jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('pending','sent','applied','rejected','expired','unconfirmed')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    reported_revision bigint,
    device_code integer,
    last_error varchar(64) NOT NULL DEFAULT '',
    confirmation_source varchar(16),
    PRIMARY KEY(device_id,request_id)
);
CREATE INDEX device_config_due ON device_config_requests(next_attempt_at) WHERE status IN ('pending','sent');
CREATE TABLE device_config_audit (
    sequence bigserial PRIMARY KEY,
    device_id varchar(40) NOT NULL,
    request_id varchar(40) NOT NULL,
    actor varchar(24) NOT NULL,
    event varchar(32) NOT NULL,
    detail varchar(64) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(device_id,request_id) REFERENCES device_config_requests(device_id,request_id)
);
