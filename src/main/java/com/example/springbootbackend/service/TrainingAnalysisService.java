package com.example.springbootbackend.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.exception.VetException;

@Service
@Transactional(readOnly = true)
public class TrainingAnalysisService {
    private final VetData db;
    private final VetAccess access;
    private final VetTime time;

    public TrainingAnalysisService(VetData db, VetAccess access, VetTime time) {
        this.db = db;
        this.access = access;
        this.time = time;
    }

    public Map<String, Object> report(String email, UUID horseId, LocalDate from, LocalDate to) {
        access.requireRole(email, "HEAD_TRAINER");
        access.requireHorse(horseId.toString(), false);
        LocalDate end = to == null ? time.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start) || Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 366)
            throw VetException.invalid("from", "Khoang bao cao phai tu 0 den 366 ngay");
        var sessions = db.query("SELECT ts.*,ce.event_date,ce.start_time,ce.end_time,g.full_name AS groom_name,"
                + "sim.id AS simulation_id,sim.status AS simulation_status,sim.stop_reason,sim.duration_seconds,"
                + "sim.created_at AS simulation_started_at,sim.stopped_at,sim.actual_distance_meters,sim.recovery_heart_rate,sim.stamina_score AS estimated_stamina_score,"
                + "m.avg_speed_kmh,m.max_heart_rate,m.has_injury_alert"
                + " FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id"
                + " LEFT JOIN users g ON g.user_id=ts.assigned_groom_id"
                + " LEFT JOIN race_simulations sim ON sim.training_schedule_id=ts.id AND sim.model_version>=2"
                + " AND NOT EXISTS (SELECT 1 FROM race_simulations newer WHERE newer.training_schedule_id=sim.training_schedule_id"
                + " AND (newer.created_at>sim.created_at OR (newer.created_at=sim.created_at AND newer.id>sim.id)))"
                + " LEFT JOIN race_simulation_metrics m ON m.simulation_id=sim.id AND m.horse_id=ts.horse_id"
                + " WHERE ts.horse_id=? AND ce.event_date BETWEEN ? AND ? ORDER BY ce.event_date,ce.start_time,ts.id",
                VetRows.MAPPER, horseId.toString(), start, end);
        var plans = db.query("SELECT p.* FROM training_plans p JOIN training_plan_horses tph ON tph.training_plan_id=p.id"
                + " WHERE tph.horse_id=? AND p.start_date<=? AND p.end_date>=?",
                VetRows.MAPPER, horseId.toString(), end, start);
        Map<LocalDate, Map<String, Object>> weeks = new TreeMap<>();
        for (LocalDate d = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !d.isAfter(end); d = d.plusWeeks(1)) {
            Map<String, Object> week = new LinkedHashMap<>();
            week.put("week_start", d);
            week.put("planned_minutes", 0d);
            week.put("actual_minutes", 0d);
            week.put("completed", 0);
            week.put("missed", 0);
            week.put("cancelled", 0);
            week.put("rest", 0);
            double target = 0;
            Map<String, Map<String, Object>> applied = new LinkedHashMap<>();
            for (var plan : plans) applied.put(plan.get("id").toString(), new LinkedHashMap<>(plan));
            Set<String> frozenPlans = new HashSet<>();
            for (var session : sessions) {
                LocalDate sessionDate = (LocalDate) session.get("event_date");
                if (sessionDate.isBefore(d) || sessionDate.isAfter(d.plusDays(6)) || session.get("training_plan_id") == null
                        || session.get("snapshot_start_date") == null || session.get("snapshot_end_date") == null) continue;
                if (!frozenPlans.add(session.get("training_plan_id").toString())) continue;
                var snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("start_date", session.get("snapshot_start_date"));
                snapshot.put("end_date", session.get("snapshot_end_date"));
                snapshot.put("target_workload_minutes", session.get("snapshot_workload_minutes"));
                applied.put(session.get("training_plan_id").toString(), snapshot);
            }
            for (var plan : applied.values()) {
                LocalDate lo = max(d, start, (LocalDate) plan.get("start_date"));
                LocalDate hi = min(d.plusDays(6), end, (LocalDate) plan.get("end_date"));
                if (!hi.isBefore(lo) && plan.get("target_workload_minutes") != null)
                    target += number(plan, "target_workload_minutes") * (Duration.between(lo.atStartOfDay(), hi.plusDays(1).atStartOfDay()).toDays() / 7d);
            }
            week.put("target_minutes", Math.round(target * 100) / 100d);
            weeks.put(d, week);
        }
        var attempts = db.query("SELECT sim.*,ce.event_date FROM race_simulations sim JOIN training_schedules ts ON ts.id=sim.training_schedule_id"
                + " JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.horse_id=? AND ce.event_date BETWEEN ? AND ?"
                + " AND sim.model_version>=2 ORDER BY sim.created_at,sim.id", VetRows.MAPPER, horseId.toString(), start, end);
        for (var attempt : attempts) {
            Instant began = (Instant) attempt.get("created_at");
            Instant stopped = attempt.get("stopped_at") instanceof Instant stop ? stop : time.now();
            double seconds = Math.max(0, Math.min(number(attempt, "duration_seconds"), Duration.between(began, stopped).toSeconds()));
            var week = weeks.get(((LocalDate) attempt.get("event_date")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            add(week, "actual_minutes", seconds / 60d);
        }
        for (var session : sessions) {
            LocalDate date = (LocalDate) session.get("event_date");
            var week = weeks.get(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            String status = Objects.toString(session.get("status"), "");
            LocalTime until = (LocalTime) session.get("end_time");
            if (!"Rest".equals(session.get("session_type")) && "Scheduled".equals(status) && until != null && !LocalDateTime.of(date, until).atZone(VetTime.BUSINESS_ZONE).toInstant().isAfter(time.now())) status = "Missed";
            session.put("effective_status", status);
            boolean rest = "Rest".equals(session.get("session_type"));
            if (rest) increment(week, "rest");
            if ("Completed".equals(status)) increment(week, "completed");
            if ("Missed".equals(status)) increment(week, "missed");
            if ("Cancelled".equals(status)) increment(week, "cancelled");
            if (!rest && !"Cancelled".equals(status) && session.get("start_time") != null && until != null)
                add(week, "planned_minutes", Duration.between((LocalTime) session.get("start_time"), until).toSeconds() / 60d);
            session.put("simulation_attempts", attempts.stream().filter(a -> Objects.equals(a.get("training_schedule_id"), session.get("id")))
                    .map(a -> {
                        var attempt = new LinkedHashMap<String, Object>();
                        attempt.put("id", a.get("id"));
                        attempt.put("status", a.get("status"));
                        attempt.put("stop_reason", a.get("stop_reason"));
                        return attempt;
                    }).toList());
            double target = number(session, "target_distance_meters"), actual = number(session, "actual_distance_meters");
            Double percent = target > 0 && session.get("actual_distance_meters") != null ? Math.round(actual / target * 10000) / 100d : null;
            session.put("distance_achievement_percent", percent);
            String advice = "AWAIT_RESULTS";
            if ("Interrupted".equals(session.get("simulation_status"))) advice = "REVIEW_INTERRUPTION";
            else if (Boolean.TRUE.equals(session.get("has_injury_alert"))) advice = "REVIEW_WITH_VET";
            else if (percent != null) advice = percent < 90 ? "REVIEW_SHORTFALL" : percent > 110 ? "REVIEW_EXCESS" : "TARGET_MET";
            if ("TrialRun".equals(session.get("session_type")) && "Completed".equals(session.get("simulation_status"))) {
                Map<String, Object> previous = null;
                for (var candidate : sessions) {
                    if (candidate == session) break;
                    if ("TrialRun".equals(candidate.get("session_type")) && "Completed".equals(candidate.get("simulation_status"))
                            && Objects.equals(candidate.get("target_distance_meters"), session.get("target_distance_meters"))
                            && Objects.equals(candidate.get("target_intensity"), session.get("target_intensity"))
                            && Objects.equals(candidate.get("track_surface"), session.get("track_surface"))) previous = candidate;
                }
                if (previous != null) {
                    session.put("previous_trial_id", previous.get("id"));
                    session.put("speed_change_kmh", Math.round((number(session, "avg_speed_kmh") - number(previous, "avg_speed_kmh")) * 100) / 100d);
                    session.put("recovery_change_bpm", number(session, "recovery_heart_rate") - number(previous, "recovery_heart_rate"));
                }
            }
            session.put("recommendation", advice);
        }
        for (var week : weeks.values()) {
            double target = number(week, "target_minutes"), actual = number(week, "actual_minutes");
            week.put("workload_achievement_percent", target > 0 ? Math.round(actual / target * 10000) / 100d : null);
            week.put("actual_minutes", Math.round(actual * 100) / 100d);
        }
        return Map.of("horse_id", horseId.toString(), "from", start, "to", end, "weeks", weeks.values(), "sessions", sessions,
                "trial_runs", sessions.stream().filter(s -> "TrialRun".equals(s.get("session_type"))).toList());
    }

    private double number(Map<String, Object> row, String key) { Object v = row.get(key); return v instanceof Number n ? n.doubleValue() : 0; }
    private void increment(Map<String, Object> row, String key) { row.put(key, ((Number) row.get(key)).intValue() + 1); }
    private void add(Map<String, Object> row, String key, double value) { row.put(key, number(row, key) + value); }
    private LocalDate max(LocalDate... values) { return Arrays.stream(values).max(Comparator.naturalOrder()).orElseThrow(); }
    private LocalDate min(LocalDate... values) { return Arrays.stream(values).min(Comparator.naturalOrder()).orElseThrow(); }
}
