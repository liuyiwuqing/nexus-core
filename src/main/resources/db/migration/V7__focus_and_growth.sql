CREATE TABLE pet_profile (
    id integer PRIMARY KEY CHECK (id=1),
    experience integer NOT NULL DEFAULT 0 CHECK (experience BETWEEN 0 AND 1000000),
    coins integer NOT NULL DEFAULT 0 CHECK (coins BETWEEN 0 AND 999999),
    bond integer NOT NULL DEFAULT 0 CHECK (bond BETWEEN 0 AND 100),
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO pet_profile(id) VALUES(1);
CREATE TABLE focus_sessions (
    session_id uuid PRIMARY KEY,
    request_key uuid NOT NULL UNIQUE,
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 180),
    state varchar(16) NOT NULL DEFAULT 'active' CHECK (state IN ('active','completed','cancelled')),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE UNIQUE INDEX one_active_focus ON focus_sessions((true)) WHERE state='active';
CREATE TABLE growth_rewards (
    session_id uuid PRIMARY KEY REFERENCES focus_sessions,
    experience integer NOT NULL,
    coins integer NOT NULL,
    bond integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
