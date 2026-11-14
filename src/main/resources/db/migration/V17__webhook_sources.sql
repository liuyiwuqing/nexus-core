CREATE TABLE webhook_sources (
    source_id varchar(24) PRIMARY KEY,
    name varchar(80) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 1,
    device_ids jsonb NOT NULL,
    token_hash varchar(64) NOT NULL,
    hmac_secret varchar(64) NOT NULL,
    creation_hash varchar(64) NOT NULL,
    tokens double precision NOT NULL DEFAULT 20,
    refill_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE webhook_receipts (
    source_id varchar(24) NOT NULL REFERENCES webhook_sources,
    event_key uuid NOT NULL,
    body_hash varchar(64) NOT NULL,
    message_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(source_id,event_key)
);
CREATE INDEX webhook_receipts_rate ON webhook_receipts(source_id,created_at);
