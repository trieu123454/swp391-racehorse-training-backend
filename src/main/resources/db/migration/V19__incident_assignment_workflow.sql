ALTER TABLE stable_incidents
    ADD COLUMN is_emergency BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stable_incidents ADD COLUMN assigned_to BIGINT;
ALTER TABLE stable_incidents ADD COLUMN assigned_role VARCHAR(20);
ALTER TABLE stable_incidents ADD COLUMN assignment_note TEXT;
ALTER TABLE stable_incidents ADD COLUMN assigned_by BIGINT;
ALTER TABLE stable_incidents ADD COLUMN assigned_at TIMESTAMP;
ALTER TABLE stable_incidents ADD COLUMN result_note TEXT;
ALTER TABLE stable_incidents ADD COLUMN result_by BIGINT;
ALTER TABLE stable_incidents ADD COLUMN result_at TIMESTAMP;

ALTER TABLE stable_incidents ADD CONSTRAINT fk_stable_incidents_assigned_to
    FOREIGN KEY (assigned_to) REFERENCES users (user_id) ON DELETE SET NULL;
ALTER TABLE stable_incidents ADD CONSTRAINT fk_stable_incidents_assigned_by
    FOREIGN KEY (assigned_by) REFERENCES users (user_id) ON DELETE SET NULL;
ALTER TABLE stable_incidents ADD CONSTRAINT fk_stable_incidents_result_by
    FOREIGN KEY (result_by) REFERENCES users (user_id) ON DELETE SET NULL;
ALTER TABLE stable_incidents ADD CONSTRAINT ck_stable_incidents_assigned_role
    CHECK (assigned_role IS NULL OR assigned_role IN ('VETERINARIAN', 'GROOM', 'CLUB_MANAGER'));

CREATE INDEX idx_stable_incidents_assigned_to_status ON stable_incidents (assigned_to, status);
