CREATE TABLE devices (
    device_id varchar(40) PRIMARY KEY,
    name varchar(80) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    last_seen timestamptz,
    online boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inbox_messages (
    message_id uuid PRIMARY KEY,
    connector_id varchar(64) NOT NULL,
    source varchar(32) NOT NULL,
    upstream_event_id varchar(160) NOT NULL,
    event_type varchar(64) NOT NULL,
    title varchar(160) NOT NULL,
    content text NOT NULL,
    summary text NOT NULL,
    sender varchar(160) NOT NULL,
    original_url text NOT NULL,
    priority varchar(12) NOT NULL CHECK (priority IN ('low','normal','high','critical')),
    created_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    read_at timestamptz,
    action_status varchar(24) NOT NULL DEFAULT 'none',
    metadata jsonb NOT NULL DEFAULT '{}',
    UNIQUE (connector_id, upstream_event_id)
);
CREATE INDEX inbox_received ON inbox_messages (received_at DESC, message_id);

CREATE TABLE deliveries (
    event_id uuid PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES inbox_messages,
    device_id varchar(40) NOT NULL REFERENCES devices,
    status varchar(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','sent','received','displayed','read','failed','expired')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(64),
    received_at timestamptz,
    displayed_at timestamptz,
    read_at timestamptz,
    UNIQUE (message_id, device_id)
);
CREATE INDEX deliveries_due ON deliveries (next_attempt_at) WHERE status IN ('pending','sent');

CREATE TABLE connector_state (
    connector_id varchar(64) PRIMARY KEY,
    last_success_at timestamptz,
    last_error varchar(64),
    cursor_value text
);
