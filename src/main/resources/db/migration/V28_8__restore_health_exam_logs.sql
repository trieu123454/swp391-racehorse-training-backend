-- V40 consolidated this table into health_exams on the deployed database.
-- This maintenance branch still reads and writes relational examination history.
-- Keep any inline history columns untouched; never rewrite existing examinations.
CREATE TABLE IF NOT EXISTS health_exam_logs (
    id CHAR(36) PRIMARY KEY,
    health_exam_id CHAR(36) NOT NULL REFERENCES health_exams(id) ON DELETE CASCADE,
    edited_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    old_data_json TEXT,
    new_data_json TEXT,
    edited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_health_exam_logs_health_exam_id ON health_exam_logs(health_exam_id);
CREATE INDEX IF NOT EXISTS idx_health_exam_logs_edited_by ON health_exam_logs(edited_by);
