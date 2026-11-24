ALTER TABLE horse_registration_history ADD COLUMN review_note TEXT;
ALTER TABLE horse_registration_history ADD COLUMN from_status VARCHAR(30);
ALTER TABLE horse_registration_history ADD COLUMN to_status VARCHAR(30);
ALTER TABLE horses ADD COLUMN IF NOT EXISTS microchip_number VARCHAR(100);
ALTER TABLE horses ADD COLUMN IF NOT EXISTS passport_number VARCHAR(100);
