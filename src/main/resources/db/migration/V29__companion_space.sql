CREATE TABLE device_companion_observations (
    device_id varchar(40) PRIMARY KEY REFERENCES devices ON DELETE CASCADE,
    body jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE device_companion_boots (
    device_id varchar(40) NOT NULL REFERENCES devices ON DELETE CASCADE,
    boot_id varchar(32) NOT NULL,
    PRIMARY KEY(device_id,boot_id)
);
CREATE TABLE device_appearance_requests (
    device_id varchar(40) NOT NULL REFERENCES devices ON DELETE CASCADE,
    request_id uuid NOT NULL,
    expected_revision bigint NOT NULL CHECK(expected_revision BETWEEN 0 AND 4294967294),
    appearance jsonb NOT NULL,
    envelope jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('pending','sent','applied','rejected','expired','unconfirmed')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(64) NOT NULL DEFAULT '',
    PRIMARY KEY(device_id,request_id)
);
CREATE INDEX appearance_due ON device_appearance_requests(next_attempt_at) WHERE status IN ('pending','sent');
