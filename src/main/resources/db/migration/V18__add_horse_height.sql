ALTER TABLE horses ADD COLUMN height_cm DECIMAL(5,1);

ALTER TABLE horses ADD CONSTRAINT ck_horse_height
    CHECK (height_cm IS NULL OR height_cm BETWEEN 50 AND 250);
