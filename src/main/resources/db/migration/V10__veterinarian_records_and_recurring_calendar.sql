ALTER TABLE injury_markers ADD COLUMN recovery_status VARCHAR(20) NOT NULL DEFAULT 'Active';
ALTER TABLE injury_markers ADD COLUMN marked_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL;
ALTER TABLE injury_markers ADD CONSTRAINT ck_injury_recovery CHECK (recovery_status IN ('Active','Recovering','Recovered'));
CREATE INDEX idx_injury_marked_by ON injury_markers(marked_by);
CREATE INDEX idx_injury_history ON injury_markers(horse_id,body_part,marked_at);
ALTER TABLE calendar_events DROP CONSTRAINT uq_calendar_source;
UPDATE calendar_events SET source_table='Periodic_Care_Schedules'
WHERE source_table='periodic_care_schedules';
UPDATE calendar_events SET event_type='MedicalCheckup'
WHERE source_table='Periodic_Care_Schedules' AND event_type='VetCheckup';
ALTER TABLE calendar_events ADD CONSTRAINT uq_calendar_source_date UNIQUE(source_table,source_id,event_date);
CREATE INDEX idx_notifications_due ON notifications(user_id,is_read,scheduled_at);
