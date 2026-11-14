CREATE TABLE message_templates (
    template_id uuid PRIMARY KEY,
    definition jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE deliveries ADD COLUMN title_override text;
ALTER TABLE deliveries ADD COLUMN summary_override text;
ALTER TABLE deliveries ADD COLUMN event_type_override varchar(64);
ALTER TABLE deliveries ADD COLUMN template_id uuid;
ALTER TABLE deliveries ADD COLUMN template_version integer;
-- Rendered delivery snapshots have the same body-retention boundary as the message.
CREATE FUNCTION purge_delivery_template_body() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.content_purged_at IS NOT NULL THEN
        UPDATE deliveries SET title_override=NULL,summary_override=NULL WHERE message_id=NEW.message_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER purge_template_body AFTER UPDATE OF content_purged_at ON inbox_messages
FOR EACH ROW EXECUTE FUNCTION purge_delivery_template_body();
