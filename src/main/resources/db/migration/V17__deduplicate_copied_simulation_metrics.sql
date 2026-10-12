-- A copied simulation result and its source race metric share an ID. Keep one
-- row in the common metrics view when the optional copy is stored.
CREATE OR REPLACE VIEW shared_training_metrics AS
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
    LEFT JOIN training_schedules ts ON ts.id=sim.training_schedule_id
    WHERE NOT EXISTS (SELECT 1 FROM training_metrics_logs copied WHERE copied.id=m.id);
