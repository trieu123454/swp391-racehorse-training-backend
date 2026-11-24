ALTER TABLE races ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'Scheduled';
ALTER TABLE races ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(255);

UPDATE races SET status = 'Completed' WHERE is_simulated = TRUE AND status = 'Scheduled';

ALTER TABLE races ADD CONSTRAINT ck_races_status
    CHECK (status IN ('Scheduled', 'Completed', 'Cancelled'));

ALTER TABLE horse_race_entries ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(255);

ALTER TABLE horse_race_entries ADD CONSTRAINT ck_horse_race_entries_status
    CHECK (status IN ('Registered', 'Blocked', 'Completed', 'Cancelled'));

CREATE INDEX IF NOT EXISTS idx_horse_race_entries_race_status
    ON horse_race_entries (race_id, status);
