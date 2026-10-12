-- Keep the existing numeric authentication IDs (see docs/database/README.md).
CREATE TABLE calendar_events (
    id CHAR(36) PRIMARY KEY,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    event_type VARCHAR(30) NOT NULL,
    title VARCHAR(150),
    event_date DATE NOT NULL,
    start_time TIME,
    end_time TIME,
    status VARCHAR(20) DEFAULT 'Scheduled',
    source_table VARCHAR(50) NOT NULL,
    source_id CHAR(36) NOT NULL,
    created_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_calendar_source UNIQUE (source_table, source_id)
);
CREATE INDEX idx_calendar_horse_date ON calendar_events(horse_id, event_date);
CREATE INDEX idx_calendar_created_by ON calendar_events(created_by);

ALTER TABLE training_schedules ADD COLUMN calendar_event_id CHAR(36);
ALTER TABLE horse_race_entries ADD COLUMN calendar_event_id CHAR(36);
ALTER TABLE periodic_care_schedules ADD COLUMN calendar_event_id CHAR(36);
ALTER TABLE daily_task_logs ADD COLUMN calendar_event_id CHAR(36);

-- Reuse legacy UUIDs as event IDs. A cross-table duplicate fails safely before
-- removing any legacy dates; resolve such duplicates before retrying migration.
INSERT INTO calendar_events(id, horse_id, event_type, event_date, start_time, status, source_table, source_id, created_by)
SELECT id, horse_id, CASE WHEN session_type = 'Race' THEN 'Race' ELSE 'Training' END,
       training_date, start_time, status, 'training_schedules', id, created_by
FROM training_schedules;
UPDATE training_schedules SET calendar_event_id = id;

INSERT INTO calendar_events(id, horse_id, event_type, title, event_date, status, source_table, source_id, created_by, created_at)
SELECT e.id, e.horse_id, 'Race', r.race_name, r.race_date,
       CASE WHEN e.status IN ('Completed', 'Cancelled') THEN e.status ELSE 'Scheduled' END,
       'horse_race_entries', e.id, e.registered_by, e.created_at
FROM horse_race_entries e JOIN races r ON r.id = e.race_id;
UPDATE horse_race_entries SET calendar_event_id = id;

INSERT INTO calendar_events(id, horse_id, event_type, event_date, source_table, source_id, created_at)
SELECT id, horse_id, CASE WHEN care_type = 'MedicalCheckup' THEN 'VetCheckup' ELSE care_type END,
       next_due_date, 'periodic_care_schedules', id, created_at
FROM periodic_care_schedules;
UPDATE periodic_care_schedules SET calendar_event_id = id;

INSERT INTO calendar_events(id, horse_id, event_type, title, event_date, status, source_table, source_id, created_by)
SELECT id, horse_id, 'CareTask', task_type, task_date,
       CASE WHEN status = 'Completed' THEN 'Completed' ELSE 'Scheduled' END,
       'daily_task_logs', id, assigned_by
FROM daily_task_logs;
UPDATE daily_task_logs SET calendar_event_id = id;

ALTER TABLE training_schedules ALTER COLUMN calendar_event_id SET NOT NULL;
ALTER TABLE horse_race_entries ALTER COLUMN calendar_event_id SET NOT NULL;
ALTER TABLE daily_task_logs ALTER COLUMN calendar_event_id SET NOT NULL;
ALTER TABLE training_schedules ADD CONSTRAINT uq_training_calendar UNIQUE(calendar_event_id);
ALTER TABLE horse_race_entries ADD CONSTRAINT uq_race_calendar UNIQUE(calendar_event_id);
ALTER TABLE periodic_care_schedules ADD CONSTRAINT uq_care_calendar UNIQUE(calendar_event_id);
ALTER TABLE daily_task_logs ADD CONSTRAINT uq_task_calendar UNIQUE(calendar_event_id);
ALTER TABLE training_schedules ADD CONSTRAINT fk_training_calendar FOREIGN KEY(calendar_event_id) REFERENCES calendar_events(id) ON DELETE CASCADE;
ALTER TABLE horse_race_entries ADD CONSTRAINT fk_race_calendar FOREIGN KEY(calendar_event_id) REFERENCES calendar_events(id) ON DELETE CASCADE;
ALTER TABLE periodic_care_schedules ADD CONSTRAINT fk_care_calendar FOREIGN KEY(calendar_event_id) REFERENCES calendar_events(id) ON DELETE SET NULL;
ALTER TABLE daily_task_logs ADD CONSTRAINT fk_task_calendar FOREIGN KEY(calendar_event_id) REFERENCES calendar_events(id) ON DELETE CASCADE;

DROP INDEX idx_training_schedules_date;
ALTER TABLE training_schedules DROP COLUMN training_date;
ALTER TABLE training_schedules DROP COLUMN start_time;
ALTER TABLE daily_task_logs DROP COLUMN task_date;
