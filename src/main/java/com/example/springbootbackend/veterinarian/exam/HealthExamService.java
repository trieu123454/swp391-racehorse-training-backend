package com.example.springbootbackend.veterinarian.exam;

import com.example.springbootbackend.veterinarian.support.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HealthExamService {
    private static final String SELECT = "SELECT e.*,u.full_name AS doctor_name FROM health_exams e "
            + "LEFT JOIN users u ON u.user_id=e.doctor_id ";
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final ExamAlerts alerts;
    private final ObjectMapper json;

    public HealthExamService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time,
                             ExamAlerts alerts, ObjectMapper json) {
        this.db = db; this.access = access; this.audit = audit; this.time = time;
        this.alerts = alerts; this.json = json;
    }

    @Transactional
    public Map<String, Object> create(String email, String ip, UUID horseId, JsonNode body) {
        long doctor = access.requireUser(email, true);
        access.requireHorse(horseId.toString(), true);
        Map<String, Object> data = ExamInput.parse(body);
        data.putIfAbsent("exam_date", time.now());
        ExamInput.validate(data, time.now());
        String id = UUID.randomUUID().toString();
        data.put("id", id); data.put("horse_id", horseId.toString()); data.put("doctor_id", doctor);
        String columns = String.join(",", data.keySet());
        String placeholders = String.join(",", Collections.nCopies(data.size(), "?"));
        db.update("INSERT INTO health_exams(" + columns + ") VALUES (" + placeholders + ")",
                data.values().stream().map(VetRows::sqlValue).toArray());
        audit.record(doctor, ip, "CREATE_HEALTH_EXAM:" + id);
        return detailRecord(id);
    }

    public Map<String, Object> list(String email, UUID horseId, LocalDate from, LocalDate to, ApiPage page) {
        access.requireUser(email, true);
        access.requireHorse(horseId.toString(), false);
        if (from != null && to != null && from.isAfter(to)) throw VetException.invalid("to", "Phải >= from");
        List<Object> args = new ArrayList<>(); args.add(horseId.toString());
        String where = " WHERE e.horse_id=?";
        if (from != null) {
            where += " AND e.exam_date>=?";
            args.add(LocalDateTime.ofInstant(from.atStartOfDay(VetTime.BUSINESS_ZONE).toInstant(), ZoneOffset.UTC));
        }
        if (to != null) {
            where += " AND e.exam_date<?";
            args.add(LocalDateTime.ofInstant(to.plusDays(1).atStartOfDay(VetTime.BUSINESS_ZONE).toInstant(), ZoneOffset.UTC));
        }
        Long total = db.queryForObject("SELECT count(*) FROM health_exams e" + where, Long.class, args.toArray());
        args.add(page.limit()); args.add(page.offset());
        var rows = db.query("SELECT e.id,e.exam_date,e.doctor_id,u.full_name AS doctor_name,"
                + "e.temperature_c,e.heart_rate,e.respiratory_rate,"
                + "EXISTS(SELECT 1 FROM health_exam_logs l WHERE l.health_exam_id=e.id) AS has_edits "
                + "FROM health_exams e LEFT JOIN users u ON u.user_id=e.doctor_id" + where
                + " ORDER BY e.exam_date DESC,e.id DESC LIMIT ? OFFSET ?", VetRows.MAPPER, args.toArray());
        rows.forEach(this::addDoctor);
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit());
    }

    public Map<String, Object> detail(String email, UUID id) {
        access.requireUser(email, true);
        return detailRecord(id.toString());
    }

    public Map<String, Object> logs(String email, UUID id, ApiPage page) {
        access.requireUser(email, true);
        existing(id.toString(), false);
        var rows = db.query("SELECT l.*,u.full_name AS editor_name FROM health_exam_logs l "
                + "LEFT JOIN users u ON u.user_id=l.edited_by WHERE l.health_exam_id=? "
                + "ORDER BY l.edited_at DESC,l.id DESC LIMIT ? OFFSET ?", VetRows.MAPPER,
                id.toString(), page.limit(), page.offset());
        for (var row : rows) {
            Map<String, Object> oldData = decode((String) row.remove("old_data_json"));
            Map<String, Object> newData = decode((String) row.remove("new_data_json"));
            List<Map<String, Object>> changes = new ArrayList<>();
            for (String field : newData.keySet()) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", field); change.put("old_value", oldData.get(field));
                change.put("new_value", newData.get(field)); changes.add(change);
            }
            row.put("edited_by", VetRows.person(row.get("edited_by"), row.remove("editor_name")));
            row.put("changes", changes);
        }
        Long total = db.queryForObject("SELECT count(*) FROM health_exam_logs WHERE health_exam_id=?", Long.class, id.toString());
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit());
    }

    @Transactional
    public Map<String, Object> update(String email, String ip, UUID id, JsonNode body) {
        long editor = access.requireUser(email, true);
        var old = existing(id.toString(), true);
        var patch = ExamInput.parse(body);
        Map<String, Object> merged = new LinkedHashMap<>(old); merged.putAll(patch);
        ExamInput.validate(merged, time.now());
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        patch.forEach((field, value) -> {
            if (!same(old.get(field), value)) { before.put(field, old.get(field)); after.put(field, value); }
        });
        if (after.isEmpty()) return Map.of("changed", false);
        List<Object> args = new ArrayList<>(after.values().stream().map(VetRows::sqlValue).toList());
        args.add(id.toString());
        db.update("UPDATE health_exams SET " + String.join(",", after.keySet().stream().map(field -> field + "=?").toList())
                + " WHERE id=?", args.toArray());
        db.update("INSERT INTO health_exam_logs(id,health_exam_id,edited_by,old_data_json,new_data_json,edited_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id.toString(), editor, encode(before), encode(after), time.utcNow());
        audit.record(editor, ip, "UPDATE_HEALTH_EXAM:" + id);
        var exam = detailRecord(id.toString());
        return Map.of("changed", true, "exam", exam, "alerts", exam.get("alerts"));
    }

    private Map<String, Object> existing(String id, boolean lock) {
        // Lock the horse before the examination to coordinate with horse soft-deletion.
        var horses = db.queryForList("SELECT horse_id FROM health_exams WHERE id=?", id);
        if (horses.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy hồ sơ khám");
        access.requireHorse(horses.getFirst().get("horse_id").toString().stripTrailing(), lock);
        var rows = db.query("SELECT * FROM health_exams WHERE id=?" + (lock ? " FOR UPDATE" : ""), VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy hồ sơ khám");
        return rows.getFirst();
    }

    private Map<String, Object> detailRecord(String id) {
        existing(id, false);
        var exam = db.query(SELECT + "WHERE e.id=?", VetRows.MAPPER, id).getFirst();
        addDoctor(exam);
        var latest = db.query("SELECT l.edited_at,l.edited_by,u.full_name AS editor_name FROM health_exam_logs l "
                + "LEFT JOIN users u ON u.user_id=l.edited_by WHERE l.health_exam_id=? "
                + "ORDER BY l.edited_at DESC,l.id DESC LIMIT 1", VetRows.MAPPER, id);
        exam.put("last_edited_at", latest.isEmpty() ? null : latest.getFirst().get("edited_at"));
        exam.put("last_edited_by", latest.isEmpty() ? null : VetRows.person(latest.getFirst().get("edited_by"), latest.getFirst().get("editor_name")));
        var warnings = alerts.evaluate(exam);
        exam.put("alerts", warnings); exam.put("suggest_medical_record", !warnings.isEmpty());
        return exam;
    }

    private void addDoctor(Map<String, Object> row) {
        row.put("doctor", VetRows.person(row.get("doctor_id"), row.remove("doctor_name")));
    }

    private boolean same(Object left, Object right) {
        if (left instanceof Number && right instanceof Number)
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        return Objects.equals(left, right);
    }

    private String encode(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize exam history", e); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read exam history", e); }
    }
}
