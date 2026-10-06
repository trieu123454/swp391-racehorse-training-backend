-- Preserve the plan applied to each session independently of later plan edits.
ALTER TABLE training_schedules ADD COLUMN target_distance_meters INT;
ALTER TABLE training_schedules ADD COLUMN target_intensity VARCHAR(20);
ALTER TABLE training_schedules ADD COLUMN snapshot_stage_name VARCHAR(100);
ALTER TABLE training_schedules ADD COLUMN snapshot_objective TEXT;
ALTER TABLE training_schedules ADD COLUMN snapshot_workload_minutes INT;
ALTER TABLE training_schedules ADD COLUMN snapshot_track_surface VARCHAR(50);
ALTER TABLE training_schedules ADD COLUMN trainer_review TEXT;
ALTER TABLE training_schedules ADD COLUMN review_updated_at TIMESTAMP;
UPDATE training_schedules ts SET
 target_distance_meters=p.target_distance_meters,target_intensity=p.target_intensity,
 snapshot_stage_name=p.stage_name,snapshot_objective=p.objective,
 snapshot_workload_minutes=p.target_workload_minutes,snapshot_track_surface=p.target_track_surface
FROM training_plans p WHERE p.id=ts.training_plan_id;
ALTER TABLE race_simulations ADD COLUMN track_surface VARCHAR(50);
ALTER TABLE race_simulations ADD COLUMN model_version INT NOT NULL DEFAULT 1;
ALTER TABLE race_simulations ADD COLUMN stop_reason VARCHAR(255);
ALTER TABLE race_simulations ADD COLUMN recovery_heart_rate INT;
ALTER TABLE race_simulations ADD COLUMN stamina_score DECIMAL(5,2);
ALTER TABLE race_simulations ADD COLUMN scenario VARCHAR(20) NOT NULL DEFAULT 'Normal';
ALTER TABLE training_schedules ADD COLUMN snapshot_start_date DATE;
ALTER TABLE training_schedules ADD COLUMN snapshot_end_date DATE;
UPDATE training_schedules ts SET snapshot_start_date=p.start_date,snapshot_end_date=p.end_date FROM training_plans p WHERE p.id=ts.training_plan_id;
ALTER TABLE race_simulation_horses ADD COLUMN body_weight_kg DECIMAL(6,2);
