package com.example.springbootbackend.groom;

import com.example.springbootbackend.veterinarian.support.VetAccess;
import com.example.springbootbackend.veterinarian.support.VetException;
import com.example.springbootbackend.veterinarian.support.VetRows;
import com.example.springbootbackend.veterinarian.support.VetTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GroomHorseService {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetTime time;
    private final int assignmentWindowDays;

    public GroomHorseService(JdbcTemplate db, VetAccess access, VetTime time,
            @Value("${app.groom.assignment-window-days:14}") int assignmentWindowDays) {
        this.db = db;
        this.access = access;
        this.time = time;
        this.assignmentWindowDays = Math.max(0, assignmentWindowDays);
    }

    public Map<String, Object> myHorses(String email) {
        long groomId = access.requireRole(email, "GROOM");
        LocalDate today = time.today();
        LocalDate from = today.minusDays(assignmentWindowDays);
        LocalDate through = today.plusDays(assignmentWindowDays);
        List<Map<String, Object>> rows = db.query("SELECT h.id,h.horse_name,h.image_url,h.current_status,h.is_training_locked,"
                        + "h.stable_box_id,s.box_code,s.section FROM horses h "
                        + "LEFT JOIN stable_boxes s ON s.id=h.stable_box_id "
                        + "WHERE h.deleted_at IS NULL AND ("
                        + "EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                        + "WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') "
                        + "AND ce.event_date BETWEEN ? AND ?) OR "
                        + "EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                        + "WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') "
                        + "AND ce.event_date BETWEEN ? AND ?)) "
                        + "ORDER BY h.horse_name,h.id",
                VetRows.MAPPER, groomId, from, through, groomId, from, through);
        List<Map<String, Object>> horses = rows.stream().map(this::summary).toList();
        return Map.of("data", horses);
    }

    public Map<String, Object> calendar(String email, LocalDate requestedDate) {
        long groomId = access.requireRole(email, "GROOM");
        LocalDate date = requestedDate == null ? time.today() : requestedDate;
        List<Map<String, Object>> events = new ArrayList<>();
        var training = db.query("SELECT ts.id AS training_schedule_id,ts.status,ts.session_type,"
                        + "ce.id AS event_id,ce.event_date,ce.start_time,ce.end_time,ts.notes,"
                        + "h.id AS horse_id,h.horse_name FROM training_schedules ts "
                        + "JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                        + "JOIN horses h ON h.id=ts.horse_id "
                        + "WHERE ts.assigned_groom_id=? AND ce.event_date=? AND ce.status<>'Cancelled' "
                        + "AND h.deleted_at IS NULL ORDER BY ce.start_time NULLS LAST,ce.id",
                VetRows.MAPPER, groomId, date);
        for (Map<String, Object> row : training) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "training");
            event.put("training_schedule_id", row.get("training_schedule_id"));
            event.put("event_id", row.get("event_id"));
            event.put("event_date", row.get("event_date"));
            event.put("start_time", clock(row.get("start_time")));
            event.put("end_time", clock(row.get("end_time")));
            event.put("status", row.get("status"));
            event.put("session_type", row.get("session_type"));
            event.put("horse", horseRef(row));
            event.put("note", row.get("notes"));
            events.add(event);
        }
        var tasks = db.query("SELECT t.id AS task_id,t.task_type,t.status,h.id AS horse_id,h.horse_name "
                        + "FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                        + "JOIN horses h ON h.id=t.horse_id WHERE t.groom_id=? AND ce.event_date=? "
                        + "AND (ce.status IS NULL OR ce.status<>'Cancelled') AND h.deleted_at IS NULL "
                        + "ORDER BY CASE lower(t.task_type) "
                        + "WHEN 'feeding' THEN 0 WHEN 'cleaning' THEN 1 WHEN 'bathing' THEN 2 "
                        + "WHEN 'icebath' THEN 3 ELSE 4 END,t.task_type,h.horse_name,t.id",
                VetRows.MAPPER, groomId, date);
        for (Map<String, Object> row : tasks) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "care_task");
            event.put("task_id", row.get("task_id"));
            event.put("horse", horseRef(row));
            event.put("task_type", row.get("task_type"));
            event.put("status", row.get("status"));
            events.add(event);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date", date);
        response.put("events", events);
        return response;
    }

    public Map<String, Object> horse(String email, UUID horseId) {
        long groomId = access.requireRole(email, "GROOM");
        requireAssignedHorse(groomId, horseId.toString());
        var rows = db.query("SELECT h.id,h.horse_name,h.image_url,h.current_status,h.is_training_locked,"
                        + "h.lock_level,h.stable_box_id,s.box_code,s.section FROM horses h "
                        + "LEFT JOIN stable_boxes s ON s.id=h.stable_box_id "
                        + "WHERE h.id=? AND h.deleted_at IS NULL",
                VetRows.MAPPER, horseId.toString());
        if (rows.isEmpty()) throw forbiddenHorse();
        Map<String, Object> result = summary(rows.getFirst());
        result.put("is_training_locked", rows.getFirst().get("is_training_locked"));
        result.put("lock_level", rows.getFirst().get("lock_level"));
        return result;
    }

    public List<Map<String, Object>> dietRecords(String email, UUID horseId, LocalDate activeOn) {
        long groomId = access.requireRole(email, "GROOM");
        requireAssignedHorse(groomId, horseId.toString());
        LocalDate date = activeOn == null ? time.today() : activeOn;
        return db.query("SELECT id,feed_type,quantity_kg,feeding_frequency,special_instructions,"
                        + "effective_date,end_date FROM diet_records WHERE horse_id=? "
                        + "AND effective_date<=? AND (end_date IS NULL OR end_date>=?) "
                        + "ORDER BY feed_type,id",
                VetRows.MAPPER, horseId.toString(), date, date);
    }

    public void requireAssignedHorse(long groomId, String horseId) {
        LocalDate today = time.today();
        LocalDate from = today.minusDays(assignmentWindowDays);
        LocalDate through = today.plusDays(assignmentWindowDays);
        Long assigned = db.queryForObject("SELECT count(*) FROM horses h WHERE h.id=? AND h.deleted_at IS NULL AND ("
                        + "EXISTS (SELECT 1 FROM training_schedules ts JOIN calendar_events ce ON ce.id=ts.calendar_event_id "
                        + "WHERE ts.horse_id=h.id AND ts.assigned_groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') "
                        + "AND ce.event_date BETWEEN ? AND ?) OR "
                        + "EXISTS (SELECT 1 FROM daily_task_logs t JOIN calendar_events ce ON ce.id=t.calendar_event_id "
                        + "WHERE t.horse_id=h.id AND t.groom_id=? AND (ce.status IS NULL OR ce.status<>'Cancelled') "
                        + "AND ce.event_date BETWEEN ? AND ?))",
                Long.class, horseId, groomId, from, through, groomId, from, through);
        if (assigned == null || assigned == 0) throw forbiddenHorse();
    }

    private Map<String, Object> summary(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("horse_name", row.get("horse_name"));
        result.put("image_url", row.get("image_url"));
        result.put("stable_box", stableBox(row));
        result.put("current_status", row.get("current_status"));
        result.put("is_training_locked", row.get("is_training_locked"));
        return result;
    }

    private Object stableBox(Map<String, Object> row) {
        if (row.get("stable_box_id") == null) return null;
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("box_code", row.get("box_code"));
        stable.put("section", row.get("section"));
        return stable;
    }

    private Map<String, Object> horseRef(Map<String, Object> row) {
        Map<String, Object> horse = new LinkedHashMap<>();
        horse.put("id", row.get("horse_id"));
        horse.put("horse_name", row.get("horse_name"));
        return horse;
    }

    private String clock(Object value) {
        return value instanceof LocalTime localTime ? CLOCK.format(localTime) : null;
    }

    private VetException forbiddenHorse() {
        return new VetException(403, "FORBIDDEN", "Horse is not assigned to this Groom");
    }
}
