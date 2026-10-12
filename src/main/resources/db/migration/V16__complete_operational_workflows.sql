-- Complete stage-level training guidance and distinguish simulated race records.
ALTER TABLE training_plans ADD COLUMN target_workload_minutes INT;
ALTER TABLE training_plans ADD COLUMN target_track_surface VARCHAR(50);

ALTER TABLE races
    ADD COLUMN is_simulated BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE races r
SET is_simulated=TRUE
FROM race_simulations sim
WHERE sim.race_id=r.id;
