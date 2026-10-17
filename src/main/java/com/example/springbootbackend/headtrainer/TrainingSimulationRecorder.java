package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.notification.NotificationService;
import com.example.springbootbackend.veterinarian.support.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Sampling and summaries, called atomically by cancellation and medical locks. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TrainingSimulationRecorder {
    private final JdbcTemplate db;
    private final VetTime time;
    private final NotificationService notifications;
    private final SimulationAlertPolicy alertPolicy;

    public TrainingSimulationRecorder(JdbcTemplate db, VetTime time, NotificationService notifications,
            SimulationAlertPolicy alertPolicy) {
        this.db = db; this.time = time; this.notifications = notifications;
        this.alertPolicy = alertPolicy;
    }

    public void interruptSchedule(String scheduleId, String reason) {
        interrupt("training_schedule_id=?", scheduleId, reason);
    }

    public void interruptHorse(String horseId, String reason) {
        interrupt("id IN (SELECT simulation_id FROM race_simulation_horses WHERE horse_id=?)", horseId, reason);
    }

    private void interrupt(String filter, String value, String reason) {
        var sessions = db.query("SELECT * FROM race_simulations WHERE status='Running' AND " + filter
                + " ORDER BY id FOR UPDATE", VetRows.MAPPER, value);
        for (var session : sessions) {
            String id = session.get("id").toString();
            Instant stopped = time.now();
            db.update("UPDATE race_simulations SET status='Interrupted',stop_reason=?,stopped_at=? WHERE id=?",
                    reason, VetRows.sqlValue(stopped), id);
            session.put("status", "Interrupted"); session.put("stop_reason", reason); session.put("stopped_at", stopped);
            if (version(session) >= 2) summarize(session, elapsed(session));
        }
    }

    public int elapsed(Map<String,Object> session) {
        Instant end = session.get("stopped_at") instanceof Instant stopped ? stopped : time.now();
        return (int) Math.max(0, Math.min(number(session,"duration_seconds"),
                Duration.between((Instant) session.get("created_at"), end).getSeconds()));
    }

    public List<Map<String,Object>> capture(Map<String,Object> session, int elapsed, boolean terminal) {
        String id = session.get("id").toString();
        var horses = db.query("SELECT * FROM race_simulation_horses WHERE simulation_id=? ORDER BY lane", VetRows.MAPPER, id);
        if (horses.size() != 1 || version(session) < 2) return List.of();
        var horse = horses.getFirst();
        int duration = ((Number) session.get("duration_seconds")).intValue();
        int cadence = RaceSimulationFormula.cadence(duration);
        var previousRows = db.query("SELECT * FROM training_simulation_readings WHERE simulation_id=? ORDER BY elapsed_seconds DESC LIMIT 1", VetRows.MAPPER, id);
        Map<String,Object> previous = previousRows.isEmpty() ? null : previousRows.getFirst();
        int next = previous == null ? 0 : ((Number) previous.get("elapsed_seconds")).intValue() + cadence;
        for (int second = next; second <= elapsed; second += cadence) previous = save(session, horse, previous, second);
        if (terminal && (previous == null || number(previous,"elapsed_seconds") < elapsed)) save(session, horse, previous, elapsed);
        var readings = db.query("SELECT * FROM training_simulation_readings WHERE simulation_id=? ORDER BY elapsed_seconds", VetRows.MAPPER, id);
        Integer recoveryHeartRate = terminal && !readings.isEmpty()
                ? alertPolicy.estimateRecoveryHeartRate((int)number(horse,"base_resting_heart_rate"),maximum(readings,"heart_rate"),"Fatigue".equals(session.get("scenario")))
                : null;
        if (!alertPolicy.evaluate(horse,readings,recoveryHeartRate,cadence).isEmpty())
            notifyOnce(id, horse.get("horse_id").toString());
        return readings;
    }

    private Map<String,Object> save(Map<String,Object> session, Map<String,Object> horse, Map<String,Object> previous, int second) {
        String id = session.get("id").toString();
        double p = second / number(session,"duration_seconds");
        int seed = ((Number) horse.get("seed")).intValue();
        double speed = RaceSimulationFormula.speedAt(number(horse,"base_max_speed_kmh"),p,seed,version(session));
        double resting = number(horse,"base_resting_heart_rate");
        int delta = previous == null ? 0 : second - ((Number) previous.get("elapsed_seconds")).intValue();
        int hr = RaceSimulationFormula.heartRate(resting, previous == null ? resting : number(previous,"heart_rate"),
                delta, speed, p, "Fatigue".equals(session.get("scenario")));
        int systolic = (int) Math.round(number(horse,"base_bp_systolic") + 40 * RaceSimulationFormula.clamp(speed/60,0,1));
        int diastolic = (int) Math.round(number(horse,"base_bp_diastolic") + (RaceSimulationFormula.noise(seed,p,0x1b873593)-0.5)*6);
        BigDecimal savedSpeed = decimal(speed,3);
        db.update("INSERT INTO training_simulation_readings(simulation_id,elapsed_seconds,speed_kmh,heart_rate,bp_systolic,bp_diastolic) VALUES (?,?,?,?,?,?)",
                id,second,savedSpeed,hr,systolic,diastolic);
        return Map.of("elapsed_seconds",second,"speed_kmh",savedSpeed,"heart_rate",hr,"bp_systolic",systolic,"bp_diastolic",diastolic);
    }

    public void summarize(Map<String,Object> session, int elapsed) {
        String id = session.get("id").toString();
        if (!db.query("SELECT id FROM race_simulation_metrics WHERE simulation_id=?",VetRows.MAPPER,id).isEmpty()) return;
        var readings = capture(session,elapsed,true);
        if (readings.isEmpty()) return;
        var horse = db.query("SELECT * FROM race_simulation_horses WHERE simulation_id=?",VetRows.MAPPER,id).getFirst();
        double distance = 0;
        int maxHr = 0, maxSys = 0, maxDia = 0;
        for (int i=0; i<readings.size(); i++) {
            var reading = readings.get(i);
            maxHr = Math.max(maxHr,((Number)reading.get("heart_rate")).intValue());
            maxSys = Math.max(maxSys,((Number)reading.get("bp_systolic")).intValue());
            maxDia = Math.max(maxDia,((Number)reading.get("bp_diastolic")).intValue());
            if (i>0) {
                var previous = readings.get(i-1);
                distance += (number(reading,"speed_kmh") + number(previous,"speed_kmh"))/2/3.6
                        * (number(reading,"elapsed_seconds")-number(previous,"elapsed_seconds"));
            }
        }
        boolean completed = "Completed".equals(session.get("status"));
        boolean terminal = List.of("Completed","Interrupted").contains(session.get("status"));
        Integer recoveryHr = terminal ? alertPolicy.estimateRecoveryHeartRate(
                (int)number(horse,"base_resting_heart_rate"),maxHr,"Fatigue".equals(session.get("scenario"))) : null;
        BigDecimal stamina = terminal ? decimal(RaceSimulationFormula.clamp(10-Math.max(0,recoveryHr-number(horse,"base_resting_heart_rate"))/15,0,10),2) : null;
        int cadence = RaceSimulationFormula.cadence(((Number)session.get("duration_seconds")).intValue());
        boolean alert = !alertPolicy.evaluate(horse,readings,recoveryHr,cadence).isEmpty();
        BigDecimal average = decimal(elapsed == 0 ? 0 : distance/elapsed*3.6,2);
        String metricId = UUID.randomUUID().toString();
        Object recordedAt = VetRows.sqlValue((Instant)session.get("stopped_at"));
        db.update("UPDATE race_simulations SET actual_distance_meters=?,recovery_heart_rate=?,stamina_score=? WHERE id=?",
                decimal(distance,2),recoveryHr,stamina,id);
        db.update("INSERT INTO race_simulation_metrics(id,simulation_id,horse_id,lane,finish_time_seconds,avg_speed_kmh,max_heart_rate,max_bp_systolic,max_bp_diastolic,rank,has_injury_alert,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                metricId,id,horse.get("horse_id"),horse.get("lane"),decimal(elapsed,2),average,maxHr,maxSys,maxDia,completed?1:null,alert,recordedAt);
        db.update("INSERT INTO training_metrics_logs(id,horse_id,training_schedule_id,recorded_by,body_weight_kg,max_heart_rate,avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,bp_diastolic,is_simulated,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,TRUE,?)",
                metricId,horse.get("horse_id"),session.get("training_schedule_id"),session.get("created_by"),horse.get("body_weight_kg"),
                maxHr,average,stamina,alert,completed ? "Training sensor simulation; recovery is an estimate after 15 minutes of modeled rest"
                        : "Interrupted training sensor simulation; recovery estimate after 15 minutes of modeled rest. Reason: "
                                + session.get("stop_reason"),maxSys,maxDia,recordedAt);
    }

    private int maximum(List<Map<String,Object>> readings,String key) {
        return readings.stream().mapToInt(reading -> ((Number)reading.get(key)).intValue()).max().orElse(0);
    }

    private void notifyOnce(String simulationId, String horseId) {
        // Callers hold the simulation row lock. Persisted notifications deduplicate retries and restarts.
        var vets = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE r.role_name='VETERINARIAN'"
                + " AND u.status='APPROVED' AND u.deleted_at IS NULL AND u.must_change_password=FALSE"
                + " AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.user_id=u.user_id AND n.related_table='Race_Simulations' AND n.related_id=?)", simulationId);
        for (var vet : vets) notifications.schedule(((Number)vet.get("user_id")).longValue(),"Race_Simulations",simulationId,
                "Training simulation for horse " + horseId + " exceeded a configured monitoring threshold; review the sensor readings",time.now());
    }

    private int version(Map<String,Object> session) { return ((Number)session.get("model_version")).intValue(); }
    private double number(Map<String,Object> row,String key) { return ((Number)row.get(key)).doubleValue(); }
    private BigDecimal decimal(double value,int scale) { return BigDecimal.valueOf(value).setScale(scale,RoundingMode.HALF_UP); }
}
