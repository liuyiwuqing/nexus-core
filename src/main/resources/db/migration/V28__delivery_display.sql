-- Template-rendered display options captured per delivery at creation.
ALTER TABLE deliveries ADD COLUMN sound varchar(8);
ALTER TABLE deliveries ADD COLUMN duration_ms integer;
ALTER TABLE deliveries ADD CONSTRAINT deliveries_sound_check CHECK (sound IS NULL OR sound IN ('none','chime','alert'));
ALTER TABLE deliveries ADD CONSTRAINT deliveries_duration_check CHECK (duration_ms IS NULL OR duration_ms BETWEEN 5000 AND 30000);
