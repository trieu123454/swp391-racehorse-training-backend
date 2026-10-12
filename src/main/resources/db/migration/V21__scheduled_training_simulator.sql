-- Preserve old simulation history; new runs are tied to a scheduled workout.
ALTER TABLE race_simulations ADD COLUMN target_intensity VARCHAR(20);
ALTER TABLE race_simulation_metrics ALTER COLUMN finish_time_seconds TYPE DECIMAL(10,2);
ALTER TABLE race_simulations ADD COLUMN actual_distance_meters DECIMAL(12,2);
CREATE TABLE training_simulation_readings (
    simulation_id CHAR(36) NOT NULL REFERENCES race_simulations(id) ON DELETE CASCADE,
    elapsed_seconds INT NOT NULL,
    speed_kmh DECIMAL(6,3) NOT NULL,
    heart_rate INT NOT NULL,
    bp_systolic INT NOT NULL,
    bp_diastolic INT NOT NULL,
    PRIMARY KEY (simulation_id, elapsed_seconds)
);
ALTER TABLE race_simulations ADD COLUMN stopped_at TIMESTAMP;
