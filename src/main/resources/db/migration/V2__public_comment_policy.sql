ALTER TABLE inbox_messages ADD COLUMN data_class varchar(24) NOT NULL DEFAULT 'internal'
    CHECK (data_class IN ('internal','public_comment'));
