-- Read-only display data, deliberately separate from pet_profile/growth_rewards.
CREATE TABLE device_growth_observations (
    device_id varchar(40) PRIMARY KEY REFERENCES devices(device_id),
    body jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE device_growth_boots (
    device_id varchar(40) NOT NULL REFERENCES devices(device_id),
    boot_id varchar(32) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(device_id,boot_id)
);
