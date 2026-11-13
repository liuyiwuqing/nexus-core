CREATE TABLE ota_releases (
    release_id varchar(64) PRIMARY KEY,
    manifest jsonb NOT NULL,
    target jsonb NOT NULL,
    identity jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE device_ota_observations (
    device_id varchar(40) PRIMARY KEY REFERENCES devices(device_id),
    body jsonb NOT NULL,
    boot_id varchar(32) NOT NULL,
    report_sequence bigint NOT NULL,
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE device_ota_deployments (
    device_id varchar(40) NOT NULL REFERENCES devices(device_id),
    request_id varchar(40) NOT NULL,
    release_id varchar(64) NOT NULL REFERENCES ota_releases(release_id),
    initial_boot_id varchar(32) NOT NULL,
    target jsonb NOT NULL,
    status varchar(24) NOT NULL,
    last_error varchar(80) NOT NULL DEFAULT '',
    confirmation_source varchar(20),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(device_id,request_id)
);
CREATE UNIQUE INDEX device_ota_one_active ON device_ota_deployments(device_id)
    WHERE status NOT IN ('healthy','failed','rejected','expired','interrupted','rolled_back');
CREATE TABLE device_ota_commands (
    device_id varchar(40) NOT NULL,
    command_id varchar(40) NOT NULL,
    request_id varchar(40) NOT NULL,
    operation varchar(8) NOT NULL CHECK(operation IN ('install','reboot')),
    boot_id varchar(32) NOT NULL,
    envelope jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(device_id,command_id),
    FOREIGN KEY(device_id,request_id) REFERENCES device_ota_deployments(device_id,request_id)
);
CREATE INDEX device_ota_due ON device_ota_commands(next_attempt_at) WHERE status IN ('pending','sent');
CREATE TABLE device_ota_audit (
    sequence bigserial PRIMARY KEY,
    device_id varchar(40) NOT NULL,
    request_id varchar(40) NOT NULL,
    actor varchar(12) NOT NULL,
    event varchar(32) NOT NULL,
    detail varchar(80) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(device_id,request_id) REFERENCES device_ota_deployments(device_id,request_id)
);

-- Random boot identifiers already observed may never become current again.
CREATE TABLE device_ota_boots (
    device_id varchar(40) NOT NULL REFERENCES devices(device_id),
    boot_id varchar(32) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(device_id,boot_id)
);
