-- Keep calendar source identifiers case-sensitive and consistent with seed data.
UPDATE calendar_events
SET source_table='training_schedules'
WHERE lower(source_table)='training_schedules';

UPDATE horses
SET current_status='Monitoring'
WHERE current_status='Under Observation';

-- Preserve the stamina input used to seed each simulation for its shared metric record.
ALTER TABLE race_simulation_horses
    ADD COLUMN baseline_stamina_score DECIMAL(5,2) NOT NULL DEFAULT 5;

ALTER TABLE race_simulation_metrics
    ADD COLUMN has_injury_alert BOOLEAN NOT NULL DEFAULT FALSE;

-- A single read model exposes real training logs and race simulation results to every actor.
CREATE VIEW shared_training_metrics AS
    SELECT id,horse_id,training_schedule_id,recorded_by,body_weight_kg,max_heart_rate,
           avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,bp_diastolic,
           is_simulated,recorded_at
    FROM training_metrics_logs
    UNION ALL
    SELECT m.id,m.horse_id,
           CASE WHEN ts.horse_id=m.horse_id THEN sim.training_schedule_id ELSE NULL END,
           sim.created_by,CAST(NULL AS DECIMAL(6,2)),m.max_heart_rate,m.avg_speed_kmh,
           sh.baseline_stamina_score,m.has_injury_alert,'Race simulation result',
           m.max_bp_systolic,m.max_bp_diastolic,TRUE,m.created_at
    FROM race_simulation_metrics m
    JOIN race_simulations sim ON sim.id=m.simulation_id
    JOIN race_simulation_horses sh ON sh.simulation_id=m.simulation_id AND sh.horse_id=m.horse_id
    LEFT JOIN training_schedules ts ON ts.id=sim.training_schedule_id;
