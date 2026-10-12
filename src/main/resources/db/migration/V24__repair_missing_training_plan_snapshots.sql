-- Repair only snapshots that were not captured; never overwrite a recorded stage.
-- The current plan is a fallback: earlier versions cannot be reconstructed.
UPDATE training_schedules ts SET snapshot_stage_name=p.stage_name,
 snapshot_objective=p.objective,snapshot_workload_minutes=p.target_workload_minutes,
 snapshot_start_date=p.start_date,snapshot_end_date=p.end_date,
 snapshot_track_surface=COALESCE(ts.track_surface,p.target_track_surface)
FROM training_plans p WHERE p.id=ts.training_plan_id AND ts.snapshot_stage_name IS NULL;
