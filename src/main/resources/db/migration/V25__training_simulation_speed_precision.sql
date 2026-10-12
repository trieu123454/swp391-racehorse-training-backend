-- Preserve enough precision for slow workouts to follow the assigned distance.
ALTER TABLE race_simulation_horses ALTER COLUMN base_max_speed_kmh TYPE DECIMAL(8,4);
