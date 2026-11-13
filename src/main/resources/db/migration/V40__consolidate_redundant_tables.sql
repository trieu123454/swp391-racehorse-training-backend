-- V40: Consolidate 11 redundant 1-1 / log / lock / tick tables into their parent tables

-- 1. Merge password_reset_codes and role_change_requests into users
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_code VARCHAR(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_code_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_code_used BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_request_id CHAR(36);
ALTER TABLE users ADD COLUMN IF NOT EXISTS requested_role_id INT REFERENCES roles(role_id);
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_reason TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_status VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_created_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_reviewed_by BIGINT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_change_reviewed_at TIMESTAMP;

-- 2. Merge training_videos into training_schedules
ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_id CHAR(36);
ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_url VARCHAR(512);
ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_description VARCHAR(255);
ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_uploaded_by BIGINT;
ALTER TABLE training_schedules ADD COLUMN IF NOT EXISTS video_uploaded_at TIMESTAMP;

UPDATE training_schedules ts
SET video_id = (SELECT v.id FROM training_videos v WHERE v.training_schedule_id = ts.id ORDER BY v.uploaded_at DESC LIMIT 1),
    video_url = (SELECT v.video_url FROM training_videos v WHERE v.training_schedule_id = ts.id ORDER BY v.uploaded_at DESC LIMIT 1),
    video_description = (SELECT v.description FROM training_videos v WHERE v.training_schedule_id = ts.id ORDER BY v.uploaded_at DESC LIMIT 1),
    video_uploaded_by = (SELECT v.uploaded_by FROM training_videos v WHERE v.training_schedule_id = ts.id ORDER BY v.uploaded_at DESC LIMIT 1),
    video_uploaded_at = (SELECT v.uploaded_at FROM training_videos v WHERE v.training_schedule_id = ts.id ORDER BY v.uploaded_at DESC LIMIT 1)
WHERE EXISTS (SELECT 1 FROM training_videos v WHERE v.training_schedule_id = ts.id);

-- 3. Merge horse_registration_request_history into horse_registration_requests
ALTER TABLE horse_registration_requests ADD COLUMN IF NOT EXISTS history_json TEXT;

-- 4. Merge horse_ownership_history into horses
ALTER TABLE horses ADD COLUMN IF NOT EXISTS ownership_history_json TEXT;

-- 5. Merge health_exam_logs into health_exams
ALTER TABLE health_exams ADD COLUMN IF NOT EXISTS edit_history_json TEXT;
ALTER TABLE health_exams ADD COLUMN IF NOT EXISTS last_edited_by BIGINT;
ALTER TABLE health_exams ADD COLUMN IF NOT EXISTS last_edited_at TIMESTAMP;

-- 6. Merge training_simulation_readings into race_simulations
ALTER TABLE race_simulations ADD COLUMN IF NOT EXISTS readings_json TEXT;

-- 7. Merge race_simulation_metrics into race_simulation_horses
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS metric_id CHAR(36);
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS finish_time_seconds NUMERIC(10, 2);
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS avg_speed_kmh NUMERIC(5, 2);
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS max_heart_rate INT;
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS max_bp_systolic INT;
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS max_bp_diastolic INT;
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS rank INT;
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS has_injury_alert BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE race_simulation_horses ADD COLUMN IF NOT EXISTS metric_created_at TIMESTAMP;

UPDATE race_simulation_horses sh
SET metric_id = (SELECT m.id FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    finish_time_seconds = (SELECT m.finish_time_seconds FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    avg_speed_kmh = (SELECT m.avg_speed_kmh FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    max_heart_rate = (SELECT m.max_heart_rate FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    max_bp_systolic = (SELECT m.max_bp_systolic FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    max_bp_diastolic = (SELECT m.max_bp_diastolic FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    rank = (SELECT m.rank FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1),
    has_injury_alert = COALESCE((SELECT m.has_injury_alert FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1), FALSE),
    metric_created_at = (SELECT m.created_at FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id LIMIT 1)
WHERE EXISTS (SELECT 1 FROM race_simulation_metrics m WHERE m.simulation_id = sh.simulation_id AND m.horse_id = sh.horse_id);

-- 8. Migrate horse_image_uploads into audit_logs before dropping
INSERT INTO audit_logs (id, user_id, action_performed, ip_address, created_at)
SELECT CASE WHEN LENGTH(u.object_path) > 36
            THEN SUBSTR(u.object_path, LENGTH(u.object_path) - 35, 36)
            ELSE u.object_path END,
       u.uploaded_by,
       'UPLOAD_HORSE_IMAGE:' || u.object_path,
       '127.0.0.1',
       u.created_at
FROM horse_image_uploads u
WHERE NOT EXISTS (
    SELECT 1 FROM audit_logs a
    WHERE a.user_id = u.uploaded_by AND a.action_performed = 'UPLOAD_HORSE_IMAGE:' || u.object_path
);

-- 8b. Update shared_training_metrics view to read from race_simulation_horses instead of race_simulation_metrics
CREATE OR REPLACE VIEW shared_training_metrics AS
    SELECT id,horse_id,training_schedule_id,recorded_by,body_weight_kg,max_heart_rate,
           avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,bp_diastolic,
           is_simulated,recorded_at
    FROM training_metrics_logs
    UNION ALL
    SELECT sh.metric_id,sh.horse_id,
           CASE WHEN ts.horse_id=sh.horse_id THEN sim.training_schedule_id ELSE NULL END,
           sim.created_by,CAST(NULL AS DECIMAL(6,2)),sh.max_heart_rate,sh.avg_speed_kmh,
           sh.baseline_stamina_score,sh.has_injury_alert,'Race simulation result',
           sh.max_bp_systolic,sh.max_bp_diastolic,TRUE,sh.metric_created_at
    FROM race_simulation_horses sh
    JOIN race_simulations sim ON sim.id=sh.simulation_id
    LEFT JOIN training_schedules ts ON ts.id=sim.training_schedule_id
    WHERE sh.finish_time_seconds IS NOT NULL
      AND sh.metric_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM training_metrics_logs copied WHERE copied.id=sh.metric_id);

-- 9. Drop the 11 redundant tables
DROP TABLE IF EXISTS horse_identity_lock;
DROP TABLE IF EXISTS training_plan_logs;
DROP TABLE IF EXISTS health_exam_logs;
DROP TABLE IF EXISTS horse_registration_request_history;
DROP TABLE IF EXISTS horse_ownership_history;
DROP TABLE IF EXISTS password_reset_codes;
DROP TABLE IF EXISTS role_change_requests;
DROP TABLE IF EXISTS training_videos;
DROP TABLE IF EXISTS horse_image_uploads;
DROP TABLE IF EXISTS training_simulation_readings;
DROP TABLE IF EXISTS race_simulation_metrics;

