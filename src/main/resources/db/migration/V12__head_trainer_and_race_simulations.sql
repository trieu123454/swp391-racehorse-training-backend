-- Head trainer metrics extensions and persisted race simulation sessions.
ALTER TABLE training_metrics_logs ADD COLUMN bp_systolic INT;
ALTER TABLE training_metrics_logs ADD COLUMN bp_diastolic INT;
ALTER TABLE training_metrics_logs ADD COLUMN is_simulated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE race_simulations (
    id CHAR(36) PRIMARY KEY,
    distance_meters INT NOT NULL,
    duration_seconds INT NOT NULL,
    training_schedule_id CHAR(36) REFERENCES training_schedules(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'Running',
    created_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- The source specification does not persist participants at session creation.
-- This normalized table lets finish requests be checked against the original field
-- and preserves the seeds/base values needed to replay a demo later.
CREATE TABLE race_simulation_horses (
    simulation_id CHAR(36) NOT NULL REFERENCES race_simulations(id) ON DELETE CASCADE,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    lane INT NOT NULL,
    seed INT NOT NULL,
    base_max_speed_kmh DECIMAL(5,2) NOT NULL,
    base_resting_heart_rate INT NOT NULL,
    base_bp_systolic INT NOT NULL,
    base_bp_diastolic INT NOT NULL,
    PRIMARY KEY (simulation_id, horse_id),
    CONSTRAINT uq_race_simulation_lane UNIQUE (simulation_id, lane)
);

CREATE TABLE race_simulation_metrics (
    id CHAR(36) PRIMARY KEY,
    simulation_id CHAR(36) NOT NULL REFERENCES race_simulations(id) ON DELETE CASCADE,
    horse_id CHAR(36) NOT NULL REFERENCES horses(id) ON DELETE CASCADE,
    lane INT,
    finish_time_seconds DECIMAL(6,2) NOT NULL,
    avg_speed_kmh DECIMAL(5,2),
    max_heart_rate INT,
    max_bp_systolic INT,
    max_bp_diastolic INT,
    rank INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_race_simulation_metric_horse UNIQUE (simulation_id, horse_id)
);

CREATE INDEX idx_training_metrics_horse_recorded
    ON training_metrics_logs(horse_id, recorded_at);
CREATE INDEX idx_training_schedules_event_plan
    ON training_schedules(training_plan_id, calendar_event_id);
CREATE INDEX idx_race_simulations_created
    ON race_simulations(created_by, created_at DESC);
CREATE INDEX idx_race_simulation_horses_horse
    ON race_simulation_horses(horse_id, simulation_id);
CREATE INDEX idx_race_simulation_metrics_simulation
    ON race_simulation_metrics(simulation_id, rank);
