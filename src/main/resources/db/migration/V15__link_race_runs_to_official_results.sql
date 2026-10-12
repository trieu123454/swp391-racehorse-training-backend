-- Each completed race run is also recorded in the club race history.
ALTER TABLE race_simulations
    ADD COLUMN race_id CHAR(36) UNIQUE REFERENCES races(id) ON DELETE SET NULL;

-- Publish runs that were completed before this migration as race history too.
INSERT INTO races(id,race_name,distance_meters,race_date,description)
SELECT id,'Cuộc đua ' || CAST(created_at AS DATE) || ' · ' || SUBSTRING(id,1,8),
       distance_meters,CAST(created_at AS DATE),'Kết quả cuộc đua được hệ thống ghi nhận.'
FROM race_simulations
WHERE status='Completed';

UPDATE race_simulations
SET race_id=id
WHERE status='Completed';

INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,status,source_table,source_id,created_by,created_at)
SELECT m.id,m.horse_id,'Race',r.race_name,r.race_date,'Completed','horse_race_entries',m.id,sim.created_by,m.created_at
FROM race_simulation_metrics m
JOIN race_simulations sim ON sim.id=m.simulation_id
JOIN races r ON r.id=sim.race_id
WHERE sim.status='Completed';

INSERT INTO horse_race_entries(id,calendar_event_id,horse_id,race_id,registered_by,result_position,prize_amount,status,created_at)
SELECT m.id,m.id,m.horse_id,sim.race_id,sim.created_by,m.rank,0,'Completed',m.created_at
FROM race_simulation_metrics m
JOIN race_simulations sim ON sim.id=m.simulation_id
WHERE sim.status='Completed';
