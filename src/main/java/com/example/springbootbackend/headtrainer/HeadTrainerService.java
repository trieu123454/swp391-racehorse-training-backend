package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.care.CalendarConflictService;
import com.example.springbootbackend.veterinarian.care.CalendarEventSources;
import com.example.springbootbackend.veterinarian.horse.HorseTrainingGuard;
import com.example.springbootbackend.veterinarian.notification.NotificationService;
import com.example.springbootbackend.veterinarian.support.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Training plans, sessions, performance metrics and real race entries for Head Trainers. */
@Service
@Transactional(readOnly = true)
public class HeadTrainerService {
    private static final String[] PLAN_FIELDS = {
        "stage_name", "start_date", "end_date", "objective", "target_distance_meters",
        "target_workload_minutes", "target_track_surface", "target_intensity", "update_deadline_hours"
    };
    private static final String[] METRIC_FIELDS = {
        "body_weight_kg", "max_heart_rate", "avg_speed_kmh", "stamina_score",
        "trainer_review", "bp_systolic", "bp_diastolic"
    };

    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final HorseTrainingGuard trainingGuard;
    private final CalendarConflictService conflicts;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final int injuryAlertHeartRate;
    private final BigDecimal injuryAlertStamina;
    private final BigDecimal injuryAlertSpeed;

    public HeadTrainerService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time,
            HorseTrainingGuard trainingGuard, CalendarConflictService conflicts,
            NotificationService notifications, ObjectMapper json,
            @Value("${app.head-trainer.injury-alert-heart-rate:220}") int injuryAlertHeartRate,
            @Value("${app.head-trainer.injury-alert-stamina:3}") BigDecimal injuryAlertStamina,
            @Value("${app.head-trainer.injury-alert-speed-kmh:60}") BigDecimal injuryAlertSpeed) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.time = time;
        this.trainingGuard = trainingGuard;
        this.conflicts = conflicts;
        this.notifications = notifications;
        this.json = json;
        this.injuryAlertHeartRate = injuryAlertHeartRate;
        this.injuryAlertStamina = injuryAlertStamina;
        this.injuryAlertSpeed = injuryAlertSpeed;
    }

    public Map<String, Object> overview(String email, boolean includeSimulated) {
        requireTrainer(email);
        String realFilter = includeSimulated ? "" : " AND is_simulated=FALSE";
        var rows = db.query("WITH ranked AS ("
                + " SELECT m.*,row_number() OVER(PARTITION BY horse_id ORDER BY recorded_at DESC,id DESC) AS rn"
                + " FROM shared_training_metrics m WHERE 1=1" + realFilter + ")"
                + " SELECT h.id AS horse_id,h.horse_name,h.image_url,h.is_training_locked,h.lock_level,h.lock_reason,"
                + " latest.id AS metric_id,latest.recorded_at,latest.body_weight_kg,latest.max_heart_rate,"
                + " latest.avg_speed_kmh,latest.stamina_score,latest.has_injury_alert,latest.is_simulated,"
                + " previous.recorded_at AS previous_recorded_at,previous.body_weight_kg AS previous_body_weight_kg,"
                + " previous.max_heart_rate AS previous_max_heart_rate,previous.avg_speed_kmh AS previous_avg_speed_kmh,"
                + " previous.stamina_score AS previous_stamina_score"
                + " FROM horses h LEFT JOIN ranked latest ON latest.horse_id=h.id AND latest.rn=1"
                + " LEFT JOIN ranked previous ON previous.horse_id=h.id AND previous.rn=2"
                + " WHERE h.deleted_at IS NULL ORDER BY h.is_training_locked DESC,"
                + " COALESCE(latest.has_injury_alert,FALSE) DESC,h.horse_name,h.id", VetRows.MAPPER);
        for (var row : rows) {
            Map<String, Object> trend = new LinkedHashMap<>();
            trend.put("body_weight_kg", trend(row.get("previous_body_weight_kg"), row.get("body_weight_kg")));
            trend.put("max_heart_rate", trend(row.get("previous_max_heart_rate"), row.get("max_heart_rate")));
            trend.put("avg_speed_kmh", trend(row.get("previous_avg_speed_kmh"), row.get("avg_speed_kmh")));
            trend.put("stamina_score", trend(row.get("previous_stamina_score"), row.get("stamina_score")));
            row.put("trend", trend);
            row.remove("previous_body_weight_kg");
            row.remove("previous_max_heart_rate");
            row.remove("previous_avg_speed_kmh");
            row.remove("previous_stamina_score");
        }
        return Map.of("data", rows, "include_simulated", includeSimulated);
    }

    public List<Map<String, Object>> metrics(String email, UUID horseId, LocalDate from, LocalDate to,
            boolean includeSimulated) {
        requireTrainer(email);
        String horse = horseId.toString();
        access.requireHorse(horse, false);
        validateDateRange(from, to);
        var args = new ArrayList<Object>();
        args.add(horse);
        String where = " WHERE horse_id=?";
        if (!includeSimulated) where += " AND is_simulated=FALSE";
        if (from != null) {
            where += " AND recorded_at>=?";
            args.add(utcStart(from));
        }
        if (to != null) {
            where += " AND recorded_at<?";
            args.add(utcStart(to.plusDays(1)));
        }
        return db.query("SELECT id,horse_id,training_schedule_id,recorded_by,body_weight_kg,max_heart_rate,"
                + "avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,bp_diastolic,"
                + "is_simulated,recorded_at FROM shared_training_metrics" + where
                + " ORDER BY recorded_at,id", VetRows.MAPPER, args.toArray());
    }

    public Map<String, Object> compareMetrics(String email, String horseIds, LocalDate from, LocalDate to,
            boolean includeSimulated) {
        requireTrainer(email);
        validateDateRange(from, to);
        List<String> ids = parseHorseIds(horseIds, 8, 2);
        var horseRows = db.query("SELECT id,horse_name FROM horses WHERE deleted_at IS NULL AND id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ") ORDER BY horse_name,id",
                VetRows.MAPPER, ids.toArray());
        if (horseRows.size() != ids.size()) throw new VetException(404, "HORSE_NOT_FOUND", "Khong tim thay ngua");
        List<Map<String, Object>> result = new ArrayList<>();
        for (var horse : horseRows) {
            String id = horse.get("id").toString();
            var points = metricsForKnownHorse(id, from, to, includeSimulated);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("horse_id", id);
            item.put("horse_name", horse.get("horse_name"));
            item.put("metrics", points);
            result.add(item);
        }
        return Map.of("horses", result, "include_simulated", includeSimulated);
    }

    @Transactional
    public Map<String, Object> recordMetrics(String email, String ip, UUID scheduleId, JsonNode body) {
        long actor = requireTrainer(email);
        String schedule = scheduleId.toString();
        var rows = db.query("SELECT ts.id,ts.horse_id,ts.status,h.horse_name FROM training_schedules ts"
                + " JOIN horses h ON h.id=ts.horse_id WHERE ts.id=? AND h.deleted_at IS NULL FOR UPDATE",
                VetRows.MAPPER, schedule);
        if (rows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
        var session = rows.getFirst();
        if (!"Completed".equals(session.get("status")))
            throw new VetException(409, "SESSION_NOT_COMPLETED", "Chi ghi chi so sau khi buoi tap da hoan tat");
        assertSessionAlreadyOccurred(schedule);
        Map<String, Object> fields = VetInput.parse(body,
                "body_weight_kg:number", "max_heart_rate:int", "avg_speed_kmh:number", "stamina_score:number",
                "trainer_review:0", "bp_systolic:int", "bp_diastolic:int");
        VetInput.required(fields, "body_weight_kg", "max_heart_rate", "avg_speed_kmh", "stamina_score");
        VetInput.number(fields, "body_weight_kg", "100", "900", 2);
        VetInput.number(fields, "avg_speed_kmh", "0", "90", 2);
        VetInput.number(fields, "stamina_score", "0", "10", 2);
        int heartRate = ((Number) fields.get("max_heart_rate")).intValue();
        if (heartRate < 20 || heartRate > 260) throw VetException.invalid("max_heart_rate", "Khoang hop le: 20 den 260");
        validatePressure(fields, "bp_systolic", 40, 300);
        validatePressure(fields, "bp_diastolic", 20, 200);
        boolean alert = heartRate > injuryAlertHeartRate
                || ((BigDecimal) fields.get("avg_speed_kmh")).compareTo(injuryAlertSpeed) > 0
                || ((BigDecimal) fields.get("stamina_score")).compareTo(injuryAlertStamina) < 0;
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO training_metrics_logs(id,horse_id,training_schedule_id,recorded_by,body_weight_kg,"
                        + "max_heart_rate,avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,"
                        + "bp_diastolic,is_simulated,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,FALSE,?)",
                id, session.get("horse_id"), schedule, actor, fields.get("body_weight_kg"), heartRate,
                fields.get("avg_speed_kmh"), fields.get("stamina_score"), alert, fields.get("trainer_review"),
                fields.get("bp_systolic"), fields.get("bp_diastolic"), time.utcNow());
        db.update("UPDATE horses SET current_weight_kg=(SELECT m.body_weight_kg FROM shared_training_metrics m "
                +"WHERE m.horse_id=? AND m.body_weight_kg IS NOT NULL ORDER BY m.recorded_at DESC,m.id DESC LIMIT 1) WHERE id=?",
                session.get("horse_id"),session.get("horse_id"));
        audit.record(actor, ip, "RECORD_TRAINING_METRICS:" + id);
        if (alert) {
            var veterinarians = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                    + "WHERE r.role_name='VETERINARIAN' AND u.status='APPROVED' AND u.must_change_password=FALSE "
                    + "AND u.deleted_at IS NULL");
            String warning = "Training metric alert for " + session.get("horse_name")
                    + ": heart rate, speed, or stamina exceeded the configured safety threshold.";
            for (var veterinarian : veterinarians)
                notifications.schedule(((Number) veterinarian.get("user_id")).longValue(),
                        "Training_Metrics", id, warning, time.now());
        }
        var metric = db.query("SELECT * FROM training_metrics_logs WHERE id=?", VetRows.MAPPER, id).getFirst();
        metric.put("suggest_notify_vet", alert);
        return metric;
    }

    @Transactional
    public Map<String, Object> createPlan(String email, String ip, UUID horseId, JsonNode body) {
        long actor = requireTrainer(email);
        String horse = horseId.toString();
        trainingGuard.assertHorseNotLocked(horse);
        Map<String, Object> fields = parsePlan(body);
        VetInput.required(fields, "stage_name");
        validatePlan(fields);
        String id = UUID.randomUUID().toString();
        int updateDeadline = fields.get("update_deadline_hours") == null ? 24
                : ((Number) fields.get("update_deadline_hours")).intValue();
        db.update("INSERT INTO training_plans(id,horse_id,stage_name,start_date,end_date,objective,"
                        + "target_distance_meters,target_workload_minutes,target_track_surface,target_intensity,"
                        + "created_by,update_deadline_hours,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, horse, fields.get("stage_name"), fields.get("start_date"), fields.get("end_date"),
                fields.get("objective"), fields.get("target_distance_meters"), fields.get("target_workload_minutes"),
                fields.get("target_track_surface"), fields.get("target_intensity"),
                actor, updateDeadline, time.utcNow());
        audit.record(actor, ip, "CREATE_TRAINING_PLAN:" + id);
        return planDetail(id);
    }

    public Map<String, Object> plans(String email, UUID horseId, boolean activeOnly) {
        requireTrainer(email);
        String horse = horseId.toString();
        access.requireHorse(horse, false);
        String active = "((p.start_date IS NULL OR p.start_date<=?) AND (p.end_date IS NULL OR p.end_date>=?))";
        String where = " WHERE p.horse_id=?" + (activeOnly ? " AND " + active : "");
        var args = new ArrayList<Object>();
        args.add(time.today()); args.add(time.today());
        args.add(horse);
        if (activeOnly) { args.add(time.today()); args.add(time.today()); }
        var rows = db.query("SELECT p.*,(" + active + ") AS is_active,"
                + "(SELECT count(*) FROM training_schedules ts WHERE ts.training_plan_id=p.id) AS training_schedules_count"
                + " FROM training_plans p" + where + " ORDER BY p.start_date DESC NULLS LAST,p.created_at DESC,p.id",
                VetRows.MAPPER, args.toArray());
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> updatePlan(String email, String ip, UUID planId, JsonNode body) {
        long actor = requireTrainer(email);
        String id = planId.toString();
        var planSnapshot = planRow(id, false);
        trainingGuard.assertHorseNotLocked(planSnapshot.get("horse_id").toString());
        var current = planRow(id, true);
        Map<String, Object> patch = parsePlan(body);
        if (patch.isEmpty()) return planDetail(id);
        var merged = new LinkedHashMap<String, Object>(current);
        merged.putAll(patch);
        VetInput.required(merged, "stage_name");
        validatePlan(merged);
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        LocalDateTime nearest = db.query("SELECT min(ce.event_date+COALESCE(ce.start_time,TIME '00:00'))"
                        + " FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id"
                        + " WHERE ts.training_plan_id=? AND ce.status='Scheduled'"
                        + " AND (ce.event_date+COALESCE(ce.start_time,TIME '00:00'))>=?",
                rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null, id, now);
        if (nearest != null) {
            Object deadlineValue = current.get("update_deadline_hours");
            int deadline = deadlineValue == null ? 24 : ((Number) deadlineValue).intValue();
            long remainingSeconds = ChronoUnit.SECONDS.between(now, nearest);
            double hoursRemaining = Math.max(0, remainingSeconds / 3600.0);
            if (hoursRemaining < deadline) {
                OffsetDateTime nearestAt = nearest.atZone(VetTime.BUSINESS_ZONE).toOffsetDateTime();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("nearest_session_at", nearestAt.toString());
                details.put("hours_remaining", Math.round(hoursRemaining * 100.0) / 100.0);
                throw new VetException(409, "PLAN_UPDATE_TOO_LATE",
                        "Buoi tap gan nhat con " + Math.round(hoursRemaining * 100.0) / 100.0
                                + " gio, can sua truoc it nhat " + deadline + " gio", details);
            }
        }
        Map<String, Object> oldChanged = new LinkedHashMap<>();
        Map<String, Object> newChanged = new LinkedHashMap<>();
        Map<String, Object> assignments = new LinkedHashMap<>();
        for (String field : PLAN_FIELDS) {
            if (!patch.containsKey(field)) continue;
            Object oldValue = current.get(field);
            Object newValue = patch.get(field);
            if (!Objects.equals(normalize(oldValue), normalize(newValue))) {
                oldChanged.put(field, oldValue);
                newChanged.put(field, newValue);
                assignments.put(field, newValue);
            }
        }
        if (assignments.isEmpty()) return planDetail(id);
        assignments.put("updated_at", time.utcNow());
        List<Object> args = new ArrayList<>();
        assignments.forEach((key, value) -> args.add(VetRows.sqlValue(value)));
        args.add(id);
        db.update("UPDATE training_plans SET " + String.join(",", assignments.keySet().stream()
                .map(key -> key + "=?").toList()) + " WHERE id=?", args.toArray());
        db.update("INSERT INTO training_plan_logs(id,training_plan_id,changed_by,old_data_json,new_data_json,changed_at)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, actor, writeJson(oldChanged), writeJson(newChanged), time.utcNow());
        audit.record(actor, ip, "UPDATE_TRAINING_PLAN:" + id);
        return planDetail(id);
    }

    @Transactional
    public Map<String, Object> deletePlan(String email, String ip, UUID planId) {
        long actor = requireTrainer(email);
        String id = planId.toString();
        planRow(id, true);
        Long linkedSessions = db.queryForObject(
                "SELECT count(*) FROM training_schedules WHERE training_plan_id=?", Long.class, id);
        if (linkedSessions != null && linkedSessions > 0) {
            throw new VetException(409, "PLAN_IN_USE",
                    "Không thể xóa giáo án vì đang được gắn với " + linkedSessions
                            + " buổi tập. Hãy giữ giáo án để bảo toàn lịch sử tập luyện.",
                    Map.of("training_schedules_count", linkedSessions));
        }
        db.update("DELETE FROM training_plans WHERE id=?", id);
        audit.record(actor, ip, "DELETE_TRAINING_PLAN:" + id);
        return Map.of("id", id, "deleted", true);
    }

    @Transactional
    public Map<String, Object> createSchedule(String email, String ip, UUID horseId, JsonNode body) {
        long actor = requireTrainer(email);
        String horse = horseId.toString();
        Map<String, Object> fields = VetInput.parse(body,
                "training_plan_id:uuid", "session_type:20", "track_surface:50", "event_date:date",
                "start_time:time", "end_time:time", "assigned_groom_id:long", "notes:0");
        fields.putIfAbsent("session_type", "Training");
        VetInput.choice(fields, "session_type", "Training", "TrialRun", "Rest");
        if (!"Rest".equals(fields.get("session_type"))) trainingGuard.assertHorseNotLocked(horse);
        VetInput.required(fields, "event_date", "start_time", "end_time");
        LocalDate date = (LocalDate) fields.get("event_date");
        LocalTime start = (LocalTime) fields.get("start_time");
        LocalTime end = (LocalTime) fields.get("end_time");
        if (date.isBefore(time.today())) throw VetException.invalid("event_date", "Ngay tap khong duoc o qua khu");
        if (!end.isAfter(start)) throw VetException.invalid("end_time", "Phai sau start_time");
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        if (date.equals(now.toLocalDate()) && !LocalDateTime.of(date, start).isAfter(now))
            throw VetException.invalid("start_time", "Gio bat dau phai o phia truoc trong ngay");
        if (fields.get("training_plan_id") != null) {
            var plan = requirePlanForHorse(fields.get("training_plan_id").toString(), horse);
            LocalDate planStart = (LocalDate) plan.get("start_date");
            LocalDate planEnd = (LocalDate) plan.get("end_date");
            if (planStart != null && date.isBefore(planStart))
                throw VetException.invalid("event_date", "Ngày tập phải nằm trong thời gian của giáo án");
            if (planEnd != null && date.isAfter(planEnd))
                throw VetException.invalid("event_date", "Ngày tập phải nằm trong thời gian của giáo án");
        }
        if ((fields.get("track_surface") == null || fields.get("track_surface").toString().isBlank())
                && fields.get("training_plan_id") != null) {
            var plan = requirePlanForHorse(fields.get("training_plan_id").toString(), horse);
            fields.put("track_surface", plan.get("target_track_surface"));
        }
        Long groomId = fields.get("assigned_groom_id") == null ? null : ((Number) fields.get("assigned_groom_id")).longValue();
        if (groomId != null) requireGroom(groomId);
        var conflicting = conflicts.checkCalendarConflicts(horse, date, start, end, null,
                groomId, groomId == null ? null : "GROOM");
        if (!conflicting.isEmpty()) throw scheduleConflict(conflicting);

        String scheduleId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,"
                        + "source_table,source_id,created_by,created_at) VALUES (?,?,?,?,?,?,?,'Scheduled',?,?,?,?)",
                eventId, horse, fields.get("session_type"), fields.get("session_type"), date, start, end,
                CalendarEventSources.TRAINING_SCHEDULES, scheduleId, actor, time.utcNow());
        db.update("INSERT INTO training_schedules(id,calendar_event_id,horse_id,training_plan_id,session_type,"
                        + "track_surface,assigned_groom_id,status,notes,created_by) VALUES (?,?,?,?,?,?,?,'Scheduled',?,?)",
                scheduleId, eventId, horse, fields.get("training_plan_id"), fields.get("session_type"),
                fields.get("track_surface"), groomId, fields.get("notes"), actor);
        if (groomId != null) notifyGroom(groomId, scheduleId, horse, date, start);
        audit.record(actor, ip, "CREATE_TRAINING_SCHEDULE:" + scheduleId);
        return scheduleDetail(scheduleId);
    }

    public Map<String, Object> calendar(String email, LocalDate from, LocalDate to, UUID horseId,
            Long groomId, ApiPage page) {
        requireTrainer(email);
        validateDateRange(from, to);
        String where = " WHERE ce.event_type IN ('Training','TrialRun','Rest') AND h.deleted_at IS NULL";
        List<Object> args = new ArrayList<>();
        if (from != null) { where += " AND ce.event_date>=?"; args.add(from); }
        if (to != null) { where += " AND ce.event_date<=?"; args.add(to); }
        if (horseId != null) { where += " AND ts.horse_id=?"; args.add(horseId.toString()); }
        if (groomId != null) { where += " AND ts.assigned_groom_id=?"; args.add(groomId); }
        String fromSql = " FROM calendar_events ce JOIN training_schedules ts ON ts.calendar_event_id=ce.id"
                + " JOIN horses h ON h.id=ts.horse_id LEFT JOIN users groom ON groom.user_id=ts.assigned_groom_id"
                + " LEFT JOIN training_plans p ON p.id=ts.training_plan_id";
        Long total = db.queryForObject("SELECT count(*)" + fromSql + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(page.limit()); queryArgs.add(page.offset());
        var events = db.query("SELECT ce.id AS event_id,ce.event_type,ce.title,ce.event_date,ce.start_time,ce.end_time,"
                + "ce.status,ts.id AS training_schedule_id,ts.session_type,ts.track_surface,ts.notes,"
                + "h.id AS horse_id,h.horse_name,h.image_url,ts.training_plan_id,ts.assigned_groom_id,"
                + "p.stage_name AS plan_stage_name,p.objective AS plan_objective," 
                + "p.target_distance_meters AS plan_target_distance_meters,p.target_workload_minutes AS plan_target_workload_minutes,"
                + "p.target_track_surface AS plan_target_track_surface,p.target_intensity AS plan_target_intensity,"
                + "groom.full_name AS groom_name" + fromSql + where
                + " ORDER BY ce.event_date,ce.start_time,h.horse_name LIMIT ? OFFSET ?", VetRows.MAPPER, queryArgs.toArray());
        return Map.of("data", events, "total", total, "page", page.page(), "limit", page.limit());
    }

    public Map<String, Object> grooms(String email) {
        requireTrainer(email);
        var rows = db.query("SELECT u.user_id,u.full_name,u.email FROM users u JOIN roles r ON r.role_id=u.role_id"
                + " WHERE r.role_name='GROOM' AND u.status='APPROVED' AND u.must_change_password=FALSE"
                + " AND u.deleted_at IS NULL ORDER BY u.full_name,u.user_id", VetRows.MAPPER);
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> assignGroom(String email, String ip, UUID scheduleId, JsonNode body) {
        long actor = requireTrainer(email);
        Map<String, Object> fields = VetInput.parse(body, "groom_id:long");
        VetInput.required(fields, "groom_id");
        long groomId = ((Number) fields.get("groom_id")).longValue();
        requireGroom(groomId);
        var session = scheduleRow(scheduleId.toString(), true);
        if (!"Scheduled".equals(session.get("status")))
            throw new VetException(409, "SCHEDULE_NOT_ASSIGNABLE", "Chi co the phan cong buoi tap dang Scheduled");
        if (Objects.equals(asLong(session.get("assigned_groom_id")), groomId)) return scheduleDetail(scheduleId.toString());
        var event = eventForSchedule(scheduleId.toString());
        var conflicting = conflicts.checkCalendarConflicts(session.get("horse_id").toString(),
                (LocalDate) event.get("event_date"), (LocalTime) event.get("start_time"),
                (LocalTime) event.get("end_time"), event.get("id").toString(), groomId, "GROOM");
        if (!conflicting.isEmpty()) throw scheduleConflict(conflicting);
        db.update("UPDATE training_schedules SET assigned_groom_id=? WHERE id=?", groomId, scheduleId.toString());
        notifyGroom(groomId, scheduleId.toString(), session.get("horse_id").toString(),
                (LocalDate) event.get("event_date"), (LocalTime) event.get("start_time"));
        audit.record(actor, ip, "ASSIGN_TRAINING_GROOM:" + scheduleId + ":" + groomId);
        return scheduleDetail(scheduleId.toString());
    }

    public Map<String, Object> schedule(String email, UUID scheduleId) {
        requireTrainer(email);
        return scheduleDetail(scheduleId.toString());
    }

    @Transactional
    public Map<String, Object> updateSchedule(String email, String ip, UUID scheduleId, JsonNode body) {
        long actor = requireTrainer(email);
        String id = scheduleId.toString();
        var snapshot = scheduleRow(id, false);
        boolean horseBlocked = trainingGuard.isHorseBlocked(snapshot.get("horse_id").toString());
        var session = scheduleRow(id, true);
        Map<String, Object> fields = VetInput.parse(body, "status:20", "notes:0");
        if (fields.isEmpty()) return scheduleDetail(id);
        if (fields.containsKey("status")) {
            VetInput.choice(fields, "status", "Scheduled", "InProgress", "Completed", "Cancelled");
            String currentStatus = Objects.toString(session.get("status"), "");
            String nextStatus = Objects.toString(fields.get("status"), "");
            if (!List.of("Scheduled", "Blocked", "InProgress").contains(currentStatus) && !Objects.equals(nextStatus, currentStatus))
                throw new VetException(409, "SCHEDULE_ALREADY_CLOSED", "Buoi tap da Completed hoac Cancelled");
            if ("Scheduled".equals(nextStatus) && horseBlocked && !"Rest".equals(session.get("session_type")))
                throw new VetException(409, "TRAINING_LOCKED", "Khong the mo lai lich khi ngua dang bi khoa huan luyen");
            if ("InProgress".equals(nextStatus)) {
                if (!"Scheduled".equals(currentStatus) || "Rest".equals(session.get("session_type")))
                    throw new VetException(409, "SCHEDULE_CANNOT_START", "Buoi tap khong o trang thai co the bat dau");
                if (horseBlocked && !"Rest".equals(session.get("session_type")))
                    throw new VetException(409, "TRAINING_LOCKED", "Ngua dang bi khoa huan luyen");
                assertSessionCanStart(id);
            }
            if ("Completed".equals(nextStatus)) {
                if ("Rest".equals(session.get("session_type")) && "Scheduled".equals(currentStatus)) {
                    assertSessionAlreadyOccurred(id);
                } else {
                    if (!"InProgress".equals(currentStatus))
                        throw new VetException(409, "SESSION_NOT_STARTED", "Chi hoan tat buoi tap sau khi bat dau dung gio");
                    assertSessionAlreadyOccurred(id);
                }
            }
            if (!Objects.equals(nextStatus, currentStatus)) {
                db.update("UPDATE training_schedules SET status=? WHERE id=?", nextStatus, id);
                db.update("UPDATE calendar_events SET status=? WHERE id=?", nextStatus, eventId(session));
            }
        }
        if (fields.containsKey("notes")) db.update("UPDATE training_schedules SET notes=? WHERE id=?", fields.get("notes"), id);
        audit.record(actor, ip, "UPDATE_TRAINING_SCHEDULE:" + id);
        return scheduleDetail(id);
    }

    @Transactional
    public Map<String, Object> registerRace(String email, String ip, UUID horseId, JsonNode body) {
        long actor = requireTrainer(email);
        String horse = horseId.toString();
        trainingGuard.assertHorseNotLocked(horse);
        String readiness = db.queryForObject("SELECT readiness_status FROM horses WHERE id=?", String.class, horse);
        if (!"Ready".equals(readiness))
            throw new VetException(409, "HORSE_NOT_READY", "Ngua can duoc Vet danh gia Ready truoc khi dang ky giai");
        Map<String, Object> fields = VetInput.parse(body, "race_id:uuid");
        VetInput.required(fields, "race_id");
        String raceId = fields.get("race_id").toString();
        var raceRows = db.query("SELECT r.id,r.race_name,r.race_date,r.location,r.distance_meters FROM races r"
                        + " WHERE r.id=? AND NOT EXISTS (SELECT 1 FROM race_simulations sim WHERE sim.race_id=r.id)",
                VetRows.MAPPER, raceId);
        if (raceRows.isEmpty()) throw new VetException(404, "RACE_NOT_FOUND", "Khong tim thay giai dua");
        var race = raceRows.getFirst();
        LocalDate raceDate = (LocalDate) race.get("race_date");
        if (raceDate.isBefore(time.today())) throw VetException.invalid("race_id", "Giai dua da dien ra");
        Long existing = db.queryForObject("SELECT count(*) FROM horse_race_entries WHERE horse_id=? AND race_id=?",
                Long.class, horse, raceId);
        if (existing != null && existing > 0)
            throw new VetException(409, "RACE_ALREADY_REGISTERED", "Ngua da duoc dang ky vao giai dua nay");
        var conflicting = conflicts.checkCalendarConflicts(horse, raceDate, null, null, null, null, null);
        if (!conflicting.isEmpty()) throw scheduleConflict(conflicting);
        String entryId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,status,source_table,source_id,created_by,created_at)"
                        + " VALUES (?,?, 'Race',?,?,'Scheduled','horse_race_entries',?,?,?)",
                eventId, horse, race.get("race_name"), raceDate, entryId, actor, time.utcNow());
        db.update("INSERT INTO horse_race_entries(id,calendar_event_id,horse_id,race_id,registered_by,status,created_at)"
                        + " VALUES (?,?,?,?,?,'Registered',?)",
                entryId, eventId, horse, raceId, actor, time.utcNow());
        audit.record(actor, ip, "REGISTER_HORSE_RACE:" + entryId);
        Map<String, Object> result = new LinkedHashMap<>(race);
        result.put("entry_id", entryId);
        result.put("calendar_event_id", eventId);
        result.put("horse_id", horse);
        result.put("status", "Registered");
        return result;
    }

    public Map<String, Object> races(String email, LocalDate from, LocalDate to, ApiPage page) {
        requireTrainer(email);
        validateDateRange(from, to);
        String where = " WHERE race_date>=? AND NOT EXISTS (SELECT 1 FROM race_simulations sim WHERE sim.race_id=races.id)";
        List<Object> args = new ArrayList<>();
        args.add(from == null ? time.today() : from);
        if (to != null) { where += " AND race_date<=?"; args.add(to); }
        Long total = db.queryForObject("SELECT count(*) FROM races" + where, Long.class, args.toArray());
        args.add(page.limit()); args.add(page.offset());
        var data = db.query("SELECT * FROM races" + where + " ORDER BY race_date,race_name,id LIMIT ? OFFSET ?",
                VetRows.MAPPER, args.toArray());
        return Map.of("data", data, "total", total, "page", page.page(), "limit", page.limit());
    }

    public Map<String, Object> raceEntries(String email, UUID horseId, ApiPage page) {
        requireTrainer(email);
        access.requireHorse(horseId.toString(), false);
        String from = " FROM horse_race_entries e JOIN races r ON r.id=e.race_id"
                + " JOIN calendar_events ce ON ce.id=e.calendar_event_id WHERE e.horse_id=?";
        Long total = db.queryForObject("SELECT count(*)" + from, Long.class, horseId.toString());
        var data = db.query("SELECT e.*,r.race_name,r.race_date,r.location,r.distance_meters,ce.status AS event_status" + from
                + " ORDER BY r.race_date DESC,e.created_at DESC LIMIT ? OFFSET ?", VetRows.MAPPER,
                horseId.toString(), page.limit(), page.offset());
        return Map.of("data", data, "total", total, "page", page.page(), "limit", page.limit());
    }

    @Transactional
    public Map<String,Object> recordRaceResult(String email,String ip,UUID entryId,JsonNode body) {
        long actor=requireTrainer(email); String id=entryId.toString();
        var rows=db.query("SELECT e.id,e.horse_id,e.calendar_event_id,e.status,r.race_name,r.race_date,h.owner_id "
                +"FROM horse_race_entries e JOIN races r ON r.id=e.race_id JOIN horses h ON h.id=e.horse_id "
                +"WHERE e.id=? FOR UPDATE OF e",VetRows.MAPPER,id);
        if(rows.isEmpty()) throw new VetException(404,"RACE_ENTRY_NOT_FOUND","Khong tim thay dang ky giai");
        var entry=rows.getFirst();
        if(!"Registered".equals(entry.get("status"))) throw new VetException(409,"RACE_RESULT_CLOSED","Dang ky giai da duoc xu ly");
        if(((LocalDate)entry.get("race_date")).isAfter(time.today())) throw new VetException(409,"RACE_NOT_FINISHED","Chi ghi ket qua sau ngay thi dau");
        var fields=VetInput.parse(body,"result_position:int","prize_amount:number");
        VetInput.required(fields,"result_position");
        int position=((Number)fields.get("result_position")).intValue();
        BigDecimal prize=fields.get("prize_amount") instanceof BigDecimal amount?amount:BigDecimal.ZERO;
        if(position<1) throw VetException.invalid("result_position","Phai >= 1");
        if(prize.signum()<0 || prize.scale()>2) throw VetException.invalid("prize_amount","Phai >= 0 va toi da 2 chu so thap phan");
        db.update("UPDATE horse_race_entries SET result_position=?,prize_amount=?,status='Completed' WHERE id=?",position,prize,id);
        db.update("UPDATE calendar_events SET status='Completed' WHERE id=? AND status<>'Cancelled'",entry.get("calendar_event_id"));
        if(prize.signum()>0) db.update("INSERT INTO financial_reports(id,horse_id,transaction_type,amount,billing_period,created_at) VALUES (?,?, 'Prize',?,?,?)",
                UUID.randomUUID().toString(),entry.get("horse_id"),prize,((LocalDate)entry.get("race_date")).toString().substring(0,7),time.utcNow());
        if(entry.get("owner_id") instanceof Number ownerId)
            notifications.schedule(ownerId.longValue(),"Horse_Race_Entries",id,
                    "Race result recorded for "+entry.get("race_name")+": position "+position+".",time.now());
        audit.record(actor,ip,"RECORD_RACE_RESULT:"+id+":position="+position);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("entry_id",id); result.put("horse_id",entry.get("horse_id"));
        result.put("race_name",entry.get("race_name")); result.put("race_date",entry.get("race_date"));
        result.put("result_position",position); result.put("prize_amount",prize); result.put("status","Completed");
        return result;
    }

    @Transactional
    public Map<String,Object> addTrainingVideo(String email,String ip,UUID scheduleId,JsonNode body) {
        long actor=requireTrainer(email); String schedule=scheduleId.toString();
        var fields=VetInput.parse(body,"video_url:512","description:255"); VetInput.required(fields,"video_url");
        String videoUrl=fields.get("video_url").toString();
        try {
            URI uri=URI.create(videoUrl);
            boolean https="https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null;
            boolean localHttp="http".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null
                    && Set.of("localhost","127.0.0.1").contains(uri.getHost());
            if(!https && !localHttp) throw VetException.invalid("video_url","Use an HTTPS video link");
        } catch(IllegalArgumentException error) { throw VetException.invalid("video_url","Must be a valid HTTPS URL"); }
        var sessions=db.query("SELECT ts.id,ts.horse_id,h.owner_id,h.horse_name FROM training_schedules ts JOIN horses h ON h.id=ts.horse_id "
                +"WHERE ts.id=? AND h.deleted_at IS NULL",VetRows.MAPPER,schedule);
        if(sessions.isEmpty()) throw new VetException(404,"SCHEDULE_NOT_FOUND","Khong tim thay buoi tap");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO training_videos(id,horse_id,training_schedule_id,video_url,description,uploaded_by,uploaded_at) VALUES (?,?,?,?,?,?,?)",
                id,sessions.getFirst().get("horse_id"),schedule,videoUrl,fields.get("description"),actor,time.utcNow());
        audit.record(actor,ip,"ADD_TRAINING_VIDEO:"+id);
        if(sessions.getFirst().get("owner_id") instanceof Number ownerId)
            notifications.schedule(ownerId.longValue(),"Training_Videos",id,
                    "A new training video is available for "+sessions.getFirst().get("horse_name")+".",time.now());
        return db.query("SELECT id,horse_id,training_schedule_id,video_url,description,uploaded_by,uploaded_at FROM training_videos WHERE id=?",VetRows.MAPPER,id).getFirst();
    }

    private List<Map<String, Object>> metricsForKnownHorse(String horse, LocalDate from, LocalDate to,
            boolean includeSimulated) {
        var args = new ArrayList<Object>(); args.add(horse);
        String where = " WHERE horse_id=?";
        if (!includeSimulated) where += " AND is_simulated=FALSE";
        if (from != null) { where += " AND recorded_at>=?"; args.add(utcStart(from)); }
        if (to != null) { where += " AND recorded_at<?"; args.add(utcStart(to.plusDays(1))); }
        return db.query("SELECT id,horse_id,training_schedule_id,recorded_by,body_weight_kg,max_heart_rate,"
                + "avg_speed_kmh,stamina_score,has_injury_alert,trainer_review,bp_systolic,bp_diastolic,"
                + "is_simulated,recorded_at FROM shared_training_metrics" + where
                + " ORDER BY recorded_at,id", VetRows.MAPPER, args.toArray());
    }

    private Map<String, Object> planDetail(String id) {
        var rows = db.query("SELECT p.*,(p.start_date IS NULL OR p.start_date<=?)"
                + " AND (p.end_date IS NULL OR p.end_date>=?) AS is_active,"
                + "(SELECT count(*) FROM training_schedules ts WHERE ts.training_plan_id=p.id) AS training_schedules_count"
                + " FROM training_plans p WHERE p.id=?", VetRows.MAPPER, time.today(), time.today(), id);
        if (rows.isEmpty()) throw new VetException(404, "PLAN_NOT_FOUND", "Khong tim thay giao an");
        return rows.getFirst();
    }

    private Map<String, Object> planRow(String id, boolean lock) {
        var rows = db.query("SELECT * FROM training_plans WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "PLAN_NOT_FOUND", "Khong tim thay giao an");
        access.requireHorse(rows.getFirst().get("horse_id").toString(), false);
        return rows.getFirst();
    }

    private Map<String, Object> scheduleDetail(String id) {
        var rows = db.query("SELECT ts.*,ce.id AS event_id,ce.event_type,ce.title,ce.event_date,ce.start_time,ce.end_time,"
                + "ce.status AS event_status,h.horse_name,h.image_url,h.is_training_locked,h.lock_level,h.lock_reason,"
                + "p.stage_name AS plan_stage_name,p.start_date AS plan_start_date,p.end_date AS plan_end_date,"
                + "p.target_workload_minutes AS plan_target_workload_minutes,p.target_track_surface AS plan_target_track_surface,"
                + "groom.full_name AS groom_name FROM training_schedules ts"
                + " JOIN calendar_events ce ON ce.id=ts.calendar_event_id JOIN horses h ON h.id=ts.horse_id"
                + " LEFT JOIN training_plans p ON p.id=ts.training_plan_id"
                + " LEFT JOIN users groom ON groom.user_id=ts.assigned_groom_id WHERE ts.id=? AND h.deleted_at IS NULL",
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
        var result = rows.getFirst();
        result.put("metrics", db.query("SELECT * FROM shared_training_metrics WHERE training_schedule_id=?"
                + " ORDER BY recorded_at DESC,id DESC", VetRows.MAPPER, id));
        return result;
    }

    private Map<String, Object> scheduleRow(String id, boolean lock) {
        var rows = db.query("SELECT * FROM training_schedules WHERE id=?" + (lock ? " FOR UPDATE" : ""), VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
        access.requireHorse(rows.getFirst().get("horse_id").toString(), false);
        return rows.getFirst();
    }

    private Map<String, Object> eventForSchedule(String id) {
        var rows = db.query("SELECT ce.* FROM calendar_events ce JOIN training_schedules ts ON ts.calendar_event_id=ce.id WHERE ts.id=?",
                VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "SCHEDULE_NOT_FOUND", "Khong tim thay buoi tap");
        return rows.getFirst();
    }

    private Map<String, Object> requirePlanForHorse(String planId, String horseId) {
        var rows = db.query("SELECT id,horse_id,start_date,end_date,target_track_surface FROM training_plans"
                        + " WHERE id=? AND horse_id=? FOR UPDATE",
                VetRows.MAPPER, planId, horseId);
        if (rows.isEmpty()) throw VetException.invalid("training_plan_id", "Giao an phai thuoc cung ngua");
        return rows.getFirst();
    }

    private void requireGroom(long id) {
        var rows = db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id"
                + " WHERE u.user_id=? AND r.role_name='GROOM' AND u.status='APPROVED'"
                + " AND u.must_change_password=FALSE AND u.deleted_at IS NULL FOR UPDATE OF u", id);
        if (rows.isEmpty()) throw VetException.invalid("groom_id", "Groom phai la tai khoan GROOM dang hoat dong");
    }

    private void notifyGroom(long groomId, String scheduleId, String horseId, LocalDate date, LocalTime start) {
        var horse = db.queryForList("SELECT horse_name FROM horses WHERE id=? AND deleted_at IS NULL", horseId);
        if (horse.isEmpty()) return;
        notifications.schedule(groomId, "Training_Schedules", scheduleId,
                "Ban duoc phan cong buoi tap " + horse.getFirst().get("horse_name") + " vao " + date + " luc " + start, null);
    }

    private void assertSessionAlreadyOccurred(String scheduleId) {
        var event = eventForSchedule(scheduleId);
        LocalDate date = (LocalDate) event.get("event_date");
        LocalTime end = (LocalTime) event.get("end_time");
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        LocalDateTime scheduledEnd = LocalDateTime.of(date, end == null ? LocalTime.MIDNIGHT : end);
        if (scheduledEnd.isAfter(now))
            throw new VetException(409, "SESSION_NOT_OCCURRED", "Chi co the ghi Completed sau khi buoi tap da dien ra");
    }

    private void assertSessionCanStart(String scheduleId) {
        var event = eventForSchedule(scheduleId);
        LocalDate date = (LocalDate) event.get("event_date");
        LocalTime start = (LocalTime) event.get("start_time");
        LocalTime end = (LocalTime) event.get("end_time");
        LocalDateTime now = LocalDateTime.ofInstant(time.now(), VetTime.BUSINESS_ZONE);
        if (start == null || end == null || !date.equals(now.toLocalDate())
                || now.isBefore(LocalDateTime.of(date, start)) || !now.isBefore(LocalDateTime.of(date, end)))
            throw new VetException(409, "SESSION_OUTSIDE_TIME_WINDOW", "Chi bat dau buoi tap trong khung gio da dat");
    }

    private VetException scheduleConflict(List<Map<String, Object>> conflicting) {
        return new VetException(409, "SCHEDULE_CONFLICT", "Ngua hoac nhan su duoc phan cong da co lich trong khung gio nay",
                Map.of("conflicts", conflicting));
    }

    private Map<String, Object> parsePlan(JsonNode body) {
        return VetInput.parse(body, "stage_name:100", "start_date:date", "end_date:date", "objective:0",
                "target_distance_meters:int", "target_workload_minutes:int", "target_track_surface:50",
                "target_intensity:20", "update_deadline_hours:int");
    }

    private void validatePlan(Map<String, Object> fields) {
        if (fields.get("stage_name") != null && fields.get("stage_name").toString().isBlank())
            throw VetException.invalid("stage_name", "Bat buoc");
        if (fields.get("start_date") != null && fields.get("end_date") != null
                && ((LocalDate) fields.get("end_date")).isBefore((LocalDate) fields.get("start_date")))
            throw VetException.invalid("end_date", "Phai >= start_date");
        if (fields.get("target_intensity") != null
                && !List.of("Low", "Medium", "High").contains(fields.get("target_intensity")))
            throw VetException.invalid("target_intensity", "Gia tri hop le: Low, Medium, High");
        if (fields.get("target_distance_meters") != null && ((Number) fields.get("target_distance_meters")).intValue() <= 0)
            throw VetException.invalid("target_distance_meters", "Phai > 0");
        if (fields.get("target_workload_minutes") != null) {
            int workload = ((Number) fields.get("target_workload_minutes")).intValue();
            if (workload < 1 || workload > 1440)
                throw VetException.invalid("target_workload_minutes", "Khoang hop le: 1 den 1440 phut");
        }
        if (fields.get("target_track_surface") != null && fields.get("target_track_surface").toString().isBlank())
            throw VetException.invalid("target_track_surface", "Khong duoc de trong");
        if (fields.get("update_deadline_hours") != null && ((Number) fields.get("update_deadline_hours")).intValue() < 0)
            throw VetException.invalid("update_deadline_hours", "Phai >= 0");
    }

    private List<String> parseHorseIds(String raw, int max, int min) {
        if (raw == null || raw.isBlank()) throw VetException.invalid("horse_ids", "Bat buoc");
        List<String> ids = new ArrayList<>();
        try {
            for (String part : raw.split(",")) {
                String id = UUID.fromString(part.trim()).toString();
                if (ids.contains(id)) throw VetException.invalid("horse_ids", "Khong duoc lap ID ngua");
                ids.add(id);
            }
        } catch (IllegalArgumentException error) {
            throw VetException.invalid("horse_ids", "Moi ID phai la UUID hop le");
        }
        if (ids.size() < min || ids.size() > max)
            throw VetException.invalid("horse_ids", "So luong phai tu " + min + " den " + max);
        return ids;
    }

    private Map<String, Object> trend(Object before, Object after) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (before == null || after == null) {
            value.put("direction", "unknown"); value.put("change", null);
        } else {
            BigDecimal old = new BigDecimal(before.toString());
            BigDecimal current = new BigDecimal(after.toString());
            value.put("direction", current.compareTo(old) > 0 ? "up" : current.compareTo(old) < 0 ? "down" : "unchanged");
            value.put("change", current.subtract(old));
        }
        return value;
    }

    private void validatePressure(Map<String, Object> fields, String field, int min, int max) {
        if (fields.get(field) == null) return;
        int value = ((Number) fields.get(field)).intValue();
        if (value < min || value > max) throw VetException.invalid(field, "Khoang hop le: " + min + " den " + max);
    }

    private Long requireTrainer(String email) { return access.requireRole(email, "HEAD_TRAINER"); }
    private Long asLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private String eventId(Map<String, Object> session) { return session.get("calendar_event_id").toString(); }
    private Object normalize(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
    private String writeJson(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Could not serialize plan change", error); }
    }
    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw VetException.invalid("to", "Phai >= from");
    }
    private LocalDateTime utcStart(LocalDate date) {
        return LocalDateTime.ofInstant(date.atStartOfDay(VetTime.BUSINESS_ZONE).toInstant(), ZoneOffset.UTC);
    }
}
