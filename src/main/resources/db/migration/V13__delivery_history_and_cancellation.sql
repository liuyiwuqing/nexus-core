ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_check;
ALTER TABLE deliveries ADD CONSTRAINT deliveries_status_check
    CHECK (status IN ('pending','sent','received','displayed','read','failed','expired','cancelled'));

-- An old pending/zero-attempt row may have been retried. Its pre-migration
-- dispatch history is unknown, so only newly created rows can gain eligibility.
ALTER TABLE deliveries ADD COLUMN cancel_blocked boolean NOT NULL DEFAULT true;
ALTER TABLE deliveries ALTER COLUMN cancel_blocked SET DEFAULT false;

CREATE TABLE delivery_history (
    sequence bigserial PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES deliveries(event_id) ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    kind varchar(24) NOT NULL CHECK (kind IN ('baseline','created','attempt_reserved','retry_requested','state_changed','detail_changed')),
    previous_status varchar(16),
    status varchar(16) NOT NULL,
    attempts integer NOT NULL,
    last_error varchar(64),
    next_attempt_at timestamptz NOT NULL,
    received_at timestamptz,
    displayed_at timestamptz,
    read_at timestamptz
);
CREATE INDEX delivery_history_event ON delivery_history(event_id,sequence);

INSERT INTO delivery_history(event_id,kind,status,attempts,last_error,next_attempt_at,received_at,displayed_at,read_at)
    SELECT event_id,'baseline',status,attempts,last_error,next_attempt_at,received_at,displayed_at,read_at FROM deliveries;

CREATE FUNCTION protect_delivery_cancellation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        NEW.cancel_blocked := OLD.cancel_blocked OR NEW.cancel_blocked OR OLD.attempts>0 OR OLD.status<>'pending';
    END IF;
    NEW.cancel_blocked := NEW.cancel_blocked OR NEW.attempts>0 OR NEW.status<>'pending';
    RETURN NEW;
END;
$$;
CREATE TRIGGER delivery_cancel_guard BEFORE INSERT OR UPDATE ON deliveries
    FOR EACH ROW EXECUTE FUNCTION protect_delivery_cancellation();

-- Capture every writer (including bulk read/disable/retention transitions) in
-- the same transaction. Rolled-back changes and no-op duplicate ACKs leave no event.
CREATE FUNCTION record_delivery_history() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    event_kind varchar(24);
    before_status varchar(16);
BEGIN
    IF TG_OP='INSERT' THEN
        event_kind := 'created';
    ELSE
        IF ROW(NEW.status,NEW.attempts,NEW.last_error,NEW.next_attempt_at,NEW.received_at,NEW.displayed_at,NEW.read_at)
            IS NOT DISTINCT FROM ROW(OLD.status,OLD.attempts,OLD.last_error,OLD.next_attempt_at,OLD.received_at,OLD.displayed_at,OLD.read_at) THEN
            RETURN NEW;
        END IF;
        before_status := OLD.status;
        event_kind := CASE WHEN NEW.attempts>OLD.attempts THEN 'attempt_reserved'
            WHEN NEW.status='pending' AND OLD.status='failed' THEN 'retry_requested'
            WHEN NEW.status<>OLD.status THEN 'state_changed' ELSE 'detail_changed' END;
    END IF;
    INSERT INTO delivery_history(event_id,kind,previous_status,status,attempts,last_error,next_attempt_at,received_at,displayed_at,read_at)
        VALUES(NEW.event_id,event_kind,before_status,NEW.status,NEW.attempts,NEW.last_error,NEW.next_attempt_at,NEW.received_at,NEW.displayed_at,NEW.read_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER delivery_history_record AFTER INSERT OR UPDATE ON deliveries
    FOR EACH ROW EXECUTE FUNCTION record_delivery_history();
