ALTER TABLE diet_records ADD COLUMN diet_type VARCHAR(20) NOT NULL DEFAULT 'TREATMENT';
ALTER TABLE diet_records ADD CONSTRAINT ck_diet_records_type CHECK (diet_type IN ('STANDARD', 'TREATMENT'));
