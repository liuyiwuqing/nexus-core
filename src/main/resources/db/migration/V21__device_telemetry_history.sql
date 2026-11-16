CREATE TABLE device_telemetry_samples (
    device_id varchar(40) NOT NULL REFERENCES devices,
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    firmware_version varchar(32) NOT NULL,
    config_revision bigint NOT NULL CHECK(config_revision BETWEEN 0 AND 4294967295),
    volume smallint NOT NULL CHECK(volume BETWEEN 0 AND 100),
    brightness smallint NOT NULL CHECK(brightness BETWEEN 0 AND 100),
    muted boolean NOT NULL,
    dnd boolean NOT NULL,
    unread smallint NOT NULL CHECK(unread BETWEEN 0 AND 8),
    free_heap bigint NOT NULL,
    min_free_heap bigint NOT NULL,
    audio_underruns bigint,
    inference_max_ms bigint,
    display_max_ms bigint,
    audio_faulted boolean,
    PRIMARY KEY(device_id,observed_at)
);
CREATE INDEX device_telemetry_samples_expiry ON device_telemetry_samples(observed_at);
