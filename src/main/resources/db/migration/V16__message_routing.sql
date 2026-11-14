CREATE TABLE routing_policy (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    mode varchar(16) NOT NULL DEFAULT 'broadcast' CHECK (mode IN ('broadcast','rules')),
    version integer NOT NULL DEFAULT 1
);
INSERT INTO routing_policy(singleton) VALUES(true);
CREATE TABLE routing_rules (
    rule_id uuid PRIMARY KEY,
    definition jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE deliveries ADD COLUMN priority_override varchar(16) CHECK (priority_override IN ('low','normal','high','critical'));
