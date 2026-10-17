package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.horse.HorseTrainingGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scheduled training with a deterministic sensor substitute. */
@Service
@Transactional(readOnly = true)
public class RaceSimulationService {
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final HorseTrainingGuard trainingGuard;
    private final TrainingSimulationRecorder recorder;
    private final SimulationAlertPolicy alertPolicy;

    public RaceSimulationService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time,
            HorseTrainingGuard trainingGuard, TrainingSimulationRecorder recorder, SimulationAlertPolicy alertPolicy) {
        this.db = db; this.access = access; this.audit = audit; this.time = time;
        this.trainingGuard = trainingGuard; this.recorder = recorder;
        this.alertPolicy = alertPolicy;
    }

    @Transactional
    public Map<String, Object> create(String email, String ip, JsonNode body) {
        long actor = access.requireRole(email, "HEAD_TRAINER");
        Map<String, Object> fields = VetInput.parse(body,
                "training_schedule_id:uuid", "scenario:20");
        VetInput.required(fields, "training_schedule_id");
        fields.putIfAbsent("scenario","Normal");
        VetInput.choice(fields,"scenario","Normal","Fatigue");
        List<String> horseIds = horseIds(body == null ? null : body.get("horse_ids"));
        String scheduleId = fields.get("training_schedule_id").toString();
        trainingGuard.assertHorseNotLocked(horseIds.getFirst());
        var scheduleRows = db.query("SELECT ts.horse_id,ts.status,ts.session_type,ce.event_date,ce.start_time,ce.end_time,"
                + "ts.target_distance_meters,ts.target_intensity,ts.track_surface,ts.assigned_groom_id FROM training_schedules ts"
                + " JOIN calendar_events ce ON ce.id=ts.calendar_event_id"
                + " LEFT JOIN training_plans p ON p.id=ts.training_plan_id WHERE ts.id=? FOR UPDATE OF ts",
                VetRows.MAPPER, scheduleId);
        if (scheduleRows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
        var schedule = scheduleRows.getFirst();
        if (!List.of("Scheduled", "InProgress").contains(schedule.get("status")) || "Rest".equals(schedule.get("session_type")))
            throw new VetException(409, "SCHEDULE_NOT_STARTABLE", "Buoi tap khong the bat dau");
        if (schedule.get("assigned_groom_id") == null)
            throw new VetException(409,"GROOM_NOT_ASSIGNED","Phai phan cong Groom truoc khi mo gia lap");
        if (db.query("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=?"
                + " AND r.role_name='GROOM' AND u.status='APPROVED' AND u.deleted_at IS NULL AND u.must_change_password=FALSE",VetRows.MAPPER,schedule.get("assigned_groom_id")).isEmpty())
            throw new VetException(409,"GROOM_UNAVAILABLE","Groom duoc phan cong khong con du dieu kien");
        if (schedule.get("target_distance_meters") == null || schedule.get("target_intensity") == null || schedule.get("track_surface") == null)
            throw new VetException(409,"SESSION_TARGETS_MISSING","Can bo sung muc tieu va mat san truoc khi tap");
        if (!horseIds.getFirst().equals(schedule.get("horse_id")))
            throw VetException.invalid("horse_ids", "Gia lap phai dung ngua cua lich tap");
        var running = db.query("SELECT rs.id FROM race_simulations rs JOIN race_simulation_horses sh ON sh.simulation_id=rs.id"
                + " WHERE sh.horse_id=? AND rs.status='Running'", VetRows.MAPPER, horseIds.getFirst());
        if (!running.isEmpty()) {
            var existing = detail(email, UUID.fromString(running.getFirst().get("id").toString()));
            if ("Running".equals(existing.get("status"))) {
                if (!scheduleId.equals(existing.get("training_schedule_id")))
                    throw new VetException(409, "HORSE_ALREADY_TRAINING", "Hay ket thuc buoi gia lap dang chay cua ngua truoc");
                return existing;
            }
        }
        LocalDate date = (LocalDate) schedule.get("event_date");
        LocalTime start = (LocalTime) schedule.get("start_time"), end = (LocalTime) schedule.get("end_time");
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        if (start == null || end == null || now.isBefore(LocalDateTime.of(date, start)) || !now.isBefore(LocalDateTime.of(date, end)))
            throw new VetException(409, "SESSION_OUTSIDE_TIME_WINDOW", "Chi mo gia lap trong khung gio buoi tap");
        int availableSeconds = (int) Math.max(1, (Duration.between(time.now(), LocalDateTime.of(date, end).atZone(VetTime.BUSINESS_ZONE).toInstant()).toMillis() + 999) / 1000);
        int distance = ((Number) schedule.get("target_distance_meters")).intValue();
        String intensity = Objects.toString(schedule.get("target_intensity"), "Medium");
        String surface = schedule.get("track_surface").toString();

        var horses = db.query("SELECT id,horse_name,image_url,breed,current_weight_kg FROM horses WHERE deleted_at IS NULL AND id IN ("
                        + String.join(",", Collections.nCopies(horseIds.size(), "?")) + ")",
                VetRows.MAPPER, horseIds.toArray());
        if (horses.size() != horseIds.size()) throw new VetException(404, "HORSE_NOT_FOUND", "Khong tim thay ngua");

        String horseId = horseIds.getFirst();
        int seed = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        double stamina = averageRecentStamina(horseId);
        double capacity = RaceSimulationFormula.capacity(intensity, surface, stamina);
        int duration = RaceSimulationFormula.durationForDistance(distance, availableSeconds, seed, capacity);
        double maxSpeed = RaceSimulationFormula.baseSpeed(distance, duration, seed, capacity);
        maxSpeed = BigDecimal.valueOf(maxSpeed).setScale(4, RoundingMode.HALF_UP).doubleValue();

        String simulationId = UUID.randomUUID().toString();
        db.update("INSERT INTO race_simulations(id,distance_meters,duration_seconds,training_schedule_id,status,created_by,created_at,target_intensity,track_surface,model_version,scenario)"
                        + " VALUES (?,?,?,?,'Running',?,?,?,?,5,?)",
                simulationId, distance, duration, scheduleId, actor, time.utcNow(), intensity,surface,fields.get("scenario"));
        if (scheduleId != null) {
            db.update("UPDATE training_schedules SET status='InProgress' WHERE id=?", scheduleId);
            db.update("UPDATE calendar_events SET status='InProgress' WHERE source_table='training_schedules' AND source_id=?", scheduleId);
        }
        db.update("INSERT INTO race_simulation_horses(simulation_id,horse_id,lane,seed,base_max_speed_kmh,"
                        + "base_resting_heart_rate,base_bp_systolic,base_bp_diastolic,baseline_stamina_score,body_weight_kg)"
                        + " VALUES (?,?,1,?,?,?,?,?,?,?)",
                simulationId, horseId, seed, maxSpeed, 34, 110, 70, stamina, horses.getFirst().get("current_weight_kg"));
        audit.record(actor, ip, "CREATE_RACE_SIMULATION:" + simulationId);
        return detail(email, UUID.fromString(simulationId));
    }

    @Transactional
    public Map<String,Object> restartWithCurrentSpeedModel(String email, String ip, UUID simulationId) {
        long actor = access.requireRole(email,"HEAD_TRAINER");
        var session = lockedSession(simulationId.toString());
        reconcile(session);
        if (!"Running".equals(session.get("status")))
            throw new VetException(409,"SESSION_NOT_RUNNING","Chi co the chay lai buoi tap dang chay");
        if (((Number)session.get("model_version")).intValue() >= 5)
            throw new VetException(409,"SESSION_MODEL_CURRENT","Buoi tap da dung cong thuc toc do moi");
        String scheduleId = Objects.toString(session.get("training_schedule_id"),null);
        if (scheduleId == null)
            throw new VetException(409,"SCHEDULE_NOT_FOUND","Phien tap khong gan voi lich tap");
        var horses = db.query("SELECT horse_id FROM race_simulation_horses WHERE simulation_id=? ORDER BY lane",VetRows.MAPPER,simulationId.toString());
        if (horses.size()!=1)
            throw new VetException(409,"SESSION_HORSE_INVALID","Phien tap khong co dung mot ngua");

        int elapsed = recorder.elapsed(session);
        recorder.capture(session,elapsed,false);
        Instant stopped = time.now();
        String reason = "Restarted with updated racehorse speed model";
        db.update("UPDATE race_simulations SET status='Interrupted',stop_reason=?,stopped_at=? WHERE id=?",
                reason,VetRows.sqlValue(stopped),simulationId.toString());
        session.put("status","Interrupted"); session.put("stop_reason",reason); session.put("stopped_at",stopped);
        recorder.summarize(session,elapsed);
        audit.record(actor,ip,"RESTART_LEGACY_TRAINING_SIMULATION:"+simulationId);

        var replacement = JsonNodeFactory.instance.objectNode()
                .put("training_schedule_id",scheduleId)
                .put("scenario",Objects.toString(session.get("scenario"),"Normal"));
        replacement.putArray("horse_ids").add(horses.getFirst().get("horse_id").toString());
        return create(email,ip,replacement);
    }

    @Transactional
    public Map<String, Object> finish(String email, String ip, UUID simulationId, JsonNode body) {
        long actor = access.requireRole(email, "HEAD_TRAINER");
        var session = lockedSession(simulationId.toString());
        reconcile(session);
        if ("Running".equals(session.get("status")))
            throw new VetException(409, "SESSION_NOT_FINISHED", "Chua den gio ket thuc buoi tap");
        audit.record(actor, ip, "FINISH_RACE_SIMULATION:" + simulationId);
        var ranking = db.query("SELECT m.rank,m.horse_id,h.horse_name,m.finish_time_seconds"
                + " FROM race_simulation_metrics m JOIN horses h ON h.id=m.horse_id"
                + " WHERE m.simulation_id=? AND m.rank IS NOT NULL ORDER BY m.rank,m.lane", VetRows.MAPPER, simulationId.toString());
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("simulation_id",simulationId.toString()); response.put("race_id",null);
        response.put("race_name","Training session"); response.put("status",session.get("status"));
        response.put("ranking",ranking);
        return response;
    }

    // Use the same horse -> schedule -> simulation lock order as creation, cancellation and medical locks.
    private Map<String,Object> lockedSession(String id) {
        var owners = db.query("SELECT rs.training_schedule_id,sh.horse_id FROM race_simulations rs"
                + " LEFT JOIN race_simulation_horses sh ON sh.simulation_id=rs.id WHERE rs.id=? ORDER BY sh.lane",VetRows.MAPPER,id);
        if (owners.isEmpty()) throw new VetException(404,"SIMULATION_NOT_FOUND","Khong tim thay phien tap");
        for (var owner : owners) if (owner.get("horse_id") != null) access.requireHorse(owner.get("horse_id").toString(),true);
        if (owners.getFirst().get("training_schedule_id") != null)
            db.query("SELECT id FROM training_schedules WHERE id=? FOR UPDATE",VetRows.MAPPER,owners.getFirst().get("training_schedule_id"));
        return db.query("SELECT * FROM race_simulations WHERE id=? FOR UPDATE",VetRows.MAPPER,id).getFirst();
    }

    private void reconcile(Map<String,Object> session) {
        String id = session.get("id").toString();
        int version = ((Number)session.get("model_version")).intValue();
        if ("Running".equals(session.get("status"))) {
            var schedules = db.query("SELECT ts.status,h.is_training_locked,h.current_status FROM training_schedules ts"
                    + " JOIN horses h ON h.id=ts.horse_id WHERE ts.id=?",VetRows.MAPPER,session.get("training_schedule_id"));
            String reason = version < 2 ? "Legacy simulation replaced by scheduled training" : null;
            if (reason == null && (schedules.isEmpty() || !"InProgress".equals(schedules.getFirst().get("status"))
                    || Boolean.TRUE.equals(schedules.getFirst().get("is_training_locked"))
                    || List.of("Injured","Quarantine").contains(schedules.getFirst().get("current_status"))))
                reason = "Schedule closed or horse restricted";
            if (reason != null) {
                db.update("UPDATE race_simulations SET status='Interrupted',stop_reason=?,stopped_at=? WHERE id=?",reason,time.utcNow(),id);
                session.put("status","Interrupted"); session.put("stop_reason",reason); session.put("stopped_at",time.now());
            } else if (!time.now().isBefore(((Instant)session.get("created_at")).plusSeconds(((Number)session.get("duration_seconds")).intValue()))) {
                Instant stopped = ((Instant)session.get("created_at")).plusSeconds(((Number)session.get("duration_seconds")).intValue());
                db.update("UPDATE race_simulations SET status='Completed',stopped_at=? WHERE id=?",VetRows.sqlValue(stopped),id);
                session.put("status","Completed"); session.put("stopped_at",stopped);
                db.update("UPDATE training_schedules SET status='Completed' WHERE id=? AND status='InProgress'",session.get("training_schedule_id"));
                db.update("UPDATE calendar_events SET status='Completed' WHERE source_table='training_schedules' AND source_id=? AND status='InProgress'",session.get("training_schedule_id"));
                if (session.get("created_by") instanceof Number actor)
                    audit.record(actor.longValue(),"server-reconciliation","FINALIZE_TRAINING_SIMULATION:"+id);
            }
        }
        if (version >= 2) {
            int elapsed = recorder.elapsed(session);
            if (List.of("Completed","Interrupted").contains(session.get("status"))) recorder.summarize(session,elapsed);
            else recorder.capture(session,elapsed,false);
        }
    }

    @Transactional
    public Map<String,Object> detail(String email, UUID simulationId) {
        access.requireRole(email,"HEAD_TRAINER");
        return advance(simulationId);
    }

    /** Server maintenance is independent of whether the creator is still an active user. */
    @Transactional
    public Map<String,Object> advance(UUID simulationId) {
        String id = simulationId.toString();
        var session = lockedSession(id);
        reconcile(session);
        Map<String,Object> result = new LinkedHashMap<>(db.query("SELECT * FROM race_simulations WHERE id=?",VetRows.MAPPER,id).getFirst());
        var horses = db.query("SELECT sh.*,h.horse_name,h.image_url FROM race_simulation_horses sh JOIN horses h ON h.id=sh.horse_id"
                + " WHERE sh.simulation_id=? ORDER BY sh.lane",VetRows.MAPPER,id);
        for (var horse : horses) horse.put("base_resting_bp",Map.of("systolic",horse.get("base_bp_systolic"),"diastolic",horse.get("base_bp_diastolic")));
        int elapsed = recorder.elapsed(result), duration = ((Number)result.get("duration_seconds")).intValue();
        result.put("simulation_id",id); result.put("horses",horses);
        result.put("alert_thresholds",alertPolicy.thresholds());
        result.put("elapsed_seconds",elapsed); result.put("sampling_interval_seconds",RaceSimulationFormula.cadence(duration));
        result.put("server_time",time.now());
        double progress = elapsed/(double)duration;
        result.put("phase",progress<0.2 ? "Warmup" : progress<0.7 ? "Work" : progress<0.9 ? "Cooldown" : "Recovery");
        var readings = db.query("SELECT * FROM training_simulation_readings WHERE simulation_id=? ORDER BY elapsed_seconds",VetRows.MAPPER,id);
        result.put("readings",readings);
        Integer recoveryHeartRate = result.get("recovery_heart_rate") instanceof Number value ? value.intValue() : null;
        result.put("alerts",horses.size()==1
                ? alertPolicy.evaluate(horses.getFirst(),readings,recoveryHeartRate,RaceSimulationFormula.cadence(duration))
                : List.of());
        result.put("results",db.query("SELECT m.*,h.horse_name,sim.stamina_score FROM race_simulation_metrics m"
                + " JOIN race_simulations sim ON sim.id=m.simulation_id JOIN horses h ON h.id=m.horse_id WHERE m.simulation_id=? ORDER BY m.lane",VetRows.MAPPER,id));
        return result;
    }

    @Transactional
    public List<Map<String,Object>> activeSessions(String email) {
        access.requireRole(email,"HEAD_TRAINER");
        var ids = db.query("SELECT rs.id FROM race_simulations rs JOIN race_simulation_horses sh ON sh.simulation_id=rs.id"
                + " WHERE rs.status='Running' ORDER BY sh.horse_id,rs.id",VetRows.MAPPER);
        var result = new ArrayList<Map<String,Object>>();
        for (var row : ids) {
            var session = advance(UUID.fromString(row.get("id").toString()));
            if ("Running".equals(session.get("status"))) result.add(session);
        }
        return result;
    }

    @Transactional
    public Map<String,Object> active(String email) {
        var sessions = activeSessions(email);
        return sessions.stream().max(Comparator.comparing((Map<String,Object> row) -> (Instant)row.get("created_at"))
                .thenComparing(row -> row.get("id").toString())).orElseGet(Collections::emptyMap);
    }

    @Transactional
    public List<Map<String,Object>> createGroup(String email, String ip, JsonNode body) {
        access.requireRole(email,"HEAD_TRAINER");
        JsonNode items = body == null ? null : body.get("sessions");
        if (items == null || !items.isArray() || items.size()<1 || items.size()>5)
            throw VetException.invalid("sessions","Chon tu 1 den 5 buoi tap, moi ngua mot buoi");
        var requests = new TreeMap<String,JsonNode>();
        var scheduleIds = new HashSet<String>();
        for (var item : items) {
            String horse = horseIds(item.get("horse_ids")).getFirst();
            var fields = VetInput.parse(item,"training_schedule_id:uuid");
            VetInput.required(fields,"training_schedule_id");
            if (requests.putIfAbsent(horse,item) != null || !scheduleIds.add(fields.get("training_schedule_id").toString()))
                throw VetException.invalid("sessions","Moi ngua va buoi tap chi duoc chon mot lan");
        }
        // Lock every horse in stable order before starting any session. The group is all-or-nothing.
        for (String horse : requests.keySet()) trainingGuard.assertHorseNotLocked(horse);
        var result = new ArrayList<Map<String,Object>>();
        for (var item : requests.values()) result.add(create(email,ip,item));
        return result;
    }

    @Transactional
    public void markMissed(String email,UUID scheduleId) {
        access.requireRole(email,"HEAD_TRAINER");
        expireSchedule(scheduleId);
    }

    @Transactional
    public void expireSchedule(UUID scheduleId) {
        var rows=db.query("SELECT ts.calendar_event_id,ts.status,ce.event_date,ce.end_time FROM training_schedules ts"
                + " JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.id=? FOR UPDATE OF ts",VetRows.MAPPER,scheduleId.toString());
        if(rows.isEmpty() || !"Scheduled".equals(rows.getFirst().get("status"))) return;
        var row=rows.getFirst();
        if(row.get("end_time")==null || LocalDateTime.of((LocalDate)row.get("event_date"),(LocalTime)row.get("end_time")).atZone(VetTime.BUSINESS_ZONE).toInstant().isAfter(time.now())) return;
        db.update("UPDATE training_schedules SET status='Missed' WHERE id=?",scheduleId.toString());
        db.update("UPDATE calendar_events SET status='Missed' WHERE id=?",row.get("calendar_event_id"));
    }

    private List<String> horseIds(JsonNode node) {
        if (node == null || !node.isArray()) throw VetException.invalid("horse_ids", "Phai la mang UUID");
        if (node.size() != 1) throw VetException.invalid("horse_ids", "Moi gia lap tap luyen chi thuoc mot ngua");
        List<String> ids = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw VetException.invalid("horse_ids", "Moi ID phai la UUID hop le");
            String id;
            try { id = UUID.fromString(value.textValue()).toString(); }
            catch (IllegalArgumentException error) { throw VetException.invalid("horse_ids", "Moi ID phai la UUID hop le"); }
            if (ids.contains(id)) throw VetException.invalid("horse_ids", "Khong duoc lap ID ngua");
            ids.add(id);
        }
        return ids;
    }

    private double averageRecentStamina(String horseId) {
        var values = db.queryForList("SELECT stamina_score FROM shared_training_metrics WHERE horse_id=?"
                + " AND stamina_score IS NOT NULL ORDER BY recorded_at DESC,id DESC LIMIT 3", horseId);
        if (values.isEmpty()) return 5;
        double sum = 0;
        for (var row : values) sum += ((Number) row.get("stamina_score")).doubleValue();
        return sum / values.size();
    }


}
