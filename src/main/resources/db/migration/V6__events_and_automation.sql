CREATE TABLE automation_rules (
    rule_id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    trigger_type varchar(40) NOT NULL CHECK (trigger_type IN ('message.received','schedule.daily')),
    action_id varchar(40) NOT NULL CHECK (action_id IN ('analyze_public_comment','notify_device')),
    arguments jsonb NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 1,
    enabled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE core_events (
    event_id uuid PRIMARY KEY,
    event_key varchar(200) NOT NULL UNIQUE,
    type varchar(64) NOT NULL,
    source varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    target_rule_id uuid,
    rule_version integer,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX core_events_dispatch ON core_events(type,created_at);
CREATE TABLE automation_runs (
    run_id uuid PRIMARY KEY,
    rule_id uuid NOT NULL REFERENCES automation_rules,
    rule_version integer NOT NULL,
    event_id uuid NOT NULL REFERENCES core_events,
    state varchar(16) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','executing','enqueued','failed','cancelled')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    lease_token uuid,
    last_error varchar(64),
    result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE(rule_id,rule_version,event_id)
);
CREATE INDEX automation_runs_due ON automation_runs(next_attempt_at) WHERE state IN ('pending','executing');
