ALTER TABLE horses ADD COLUMN IF NOT EXISTS gender VARCHAR(20);
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS gender VARCHAR(20);
UPDATE stable_boxes SET status = 'Available' WHERE status = 'Full';
