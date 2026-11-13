package com.example.springbootbackend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.dto.request.ApiPage;
import com.example.springbootbackend.entity.Horse;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.HorseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class HealthExamService {
    private static final String SELECT = "SELECT e.*,u.full_name AS doctor_name FROM health_exams e "
            + "LEFT JOIN users u ON u.user_id=e.doctor_id ";

    private final VetData db;
    private final HorseRepository horseRepository;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final ExamAlerts alerts;
    private final ObjectMapper json;
    private final NotificationService notifications;

    public HealthExamService(
            VetData db,
            HorseRepository horseRepository,
            VetAccess access,
            VetAudit audit,
            VetTime time,
            ExamAlerts alerts,
            ObjectMapper json,
            NotificationService notifications
    ) {
        this.db = db;
        this.horseRepository = horseRepository;
        this.access = access;
        this.audit = audit;
        this.time = time;
        this.alerts = alerts;
        this.json = json;
        this.notifications = notifications;
    }

    @Transactional
    public Map<String, Object> create(String email, String ip, UUID horseId, JsonNode body) {
        long doctor = access.requireRole(email, "VETERINARIAN");
        access.requireHorse(horseId.toString(), true);
        Map<String, Object> data = ExamInput.parse(body);
        data.putIfAbsent("exam_date", time.now());
        ExamInput.validate(data, time.now());
        String id = UUID.randomUUID().toString();
        data.put("id", id);
        data.put("horse_id", horseId.toString());
        data.put("doctor_id", doctor);
        String columns = String.join(",", data.keySet());
        String placeholders = String.join(",", Collections.nCopies(data.size(), "?"));
        db.update("INSERT INTO health_exams(" + columns + ") VALUES (" + placeholders + ")",
                data.values().stream().map(VetRows::sqlValue).toArray());
        Horse horse = horseRepository.findById(horseId.toString()).orElse(null);
        if (horse != null && horse.getOwnerId() != null) {
            notifications.schedule(horse.getOwnerId(), "Health_Exams", id,
                    "A new veterinary health examination is available for " + horse.getHorseName() + ".",
                    time.now());
        }
        audit.record(doctor, ip, "CREATE_HEALTH_EXAM:" + id);
        return detailRecord(id);
    }

    public Map<String, Object> list(String email, UUID horseId, LocalDate from, LocalDate to, ApiPage page) {
        access.requireRole(email, "VETERINARIAN");
        access.requireHorse(horseId.toString(), false);
        if (from != null && to != null && from.isAfter(to)) throw VetException.invalid("to", "Phải >= from");
        List<Object> args = new ArrayList<>();
        args.add(horseId.toString());
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
        args.add(page.limit());
        args.add(page.offset());
        var rows = db.query("SELECT e.id,e.exam_date,e.doctor_id,u.full_name AS doctor_name,"
                + "e.temperature_c,e.heart_rate,e.respiratory_rate,"
                + "CASE WHEN e.edit_history_json IS NOT NULL THEN TRUE ELSE FALSE END AS has_edits "
                + "FROM health_exams e LEFT JOIN users u ON u.user_id=e.doctor_id" + where
                + " ORDER BY e.exam_date DESC,e.id DESC LIMIT ? OFFSET ?", VetRows.MAPPER, args.toArray());
        rows.forEach(this::addDoctor);
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit());
    }

    public Map<String, Object> detail(String email, UUID id) {
        access.requireRole(email, "VETERINARIAN");
        return detailRecord(id.toString());
    }

    public Map<String, Object> logs(String email, UUID id, ApiPage page) {
        access.requireRole(email, "VETERINARIAN");
        var exam = existing(id.toString(), false);
        List<Map<String, Object>> history = decodeHistory((String) exam.get("edit_history_json"));
        long total = history.size();
        List<Map<String, Object>> reversed = new ArrayList<>(history);
        Collections.reverse(reversed);
        int fromIdx = (int) Math.min(reversed.size(), page.offset());
        int toIdx = Math.min(reversed.size(), fromIdx + page.limit());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> entry : reversed.subList(fromIdx, toIdx)) {
            Map<String, Object> row = new LinkedHashMap<>(entry);
            Map<String, Object> oldData = decode((String) row.remove("old_data_json"));
            Map<String, Object> newData = decode((String) row.remove("new_data_json"));
            List<Map<String, Object>> changes = new ArrayList<>();
            for (String field : newData.keySet()) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", field);
                change.put("old_value", oldData.get(field));
                change.put("new_value", newData.get(field));
                changes.add(change);
            }
            Object editedBy = row.get("edited_by");
            String editorName = editedBy instanceof Number num
                    ? db.queryForList("SELECT full_name FROM users WHERE user_id=?", String.class, num.longValue()).stream().findFirst().orElse(null)
                    : null;
            row.put("edited_by", VetRows.person(editedBy, editorName));
            row.put("changes", changes);
            rows.add(row);
        }
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit());
    }

    @Transactional
    public Map<String, Object> update(String email, String ip, UUID id, JsonNode body) {
        long editor = access.requireRole(email, "VETERINARIAN");
        var old = existing(id.toString(), true);
        var patch = ExamInput.parse(body);
        Map<String, Object> merged = new LinkedHashMap<>(old);
        merged.remove("edit_history_json");
        merged.remove("last_edited_by");
        merged.remove("last_edited_at");
        merged.putAll(patch);
        ExamInput.validate(merged, time.now());
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        patch.forEach((field, value) -> {
            if (!same(old.get(field), value)) {
                before.put(field, old.get(field));
                after.put(field, value);
            }
        });
        if (after.isEmpty()) return Map.of("changed", false);
        LocalDateTime editedAt = time.utcNow();
        List<Map<String, Object>> history = new ArrayList<>(decodeHistory((String) old.get("edit_history_json")));
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("id", UUID.randomUUID().toString());
        logEntry.put("health_exam_id", id.toString());
        logEntry.put("edited_by", editor);
        logEntry.put("old_data_json", encode(before));
        logEntry.put("new_data_json", encode(after));
        logEntry.put("edited_at", editedAt.toString());
        history.add(logEntry);

        Map<String, Object> updates = new LinkedHashMap<>(after);
        updates.put("edit_history_json", encodeHistory(history));
        updates.put("last_edited_by", editor);
        updates.put("last_edited_at", editedAt);
        List<Object> args = new ArrayList<>(updates.values().stream().map(VetRows::sqlValue).toList());
        args.add(id.toString());
        db.update("UPDATE health_exams SET " + String.join(",", updates.keySet().stream().map(field -> field + "=?").toList())
                + " WHERE id=?", args.toArray());
        audit.record(editor, ip, "UPDATE_HEALTH_EXAM:" + id);
        var exam = detailRecord(id.toString());
        return Map.of("changed", true, "exam", exam, "alerts", exam.get("alerts"));
    }

    private Map<String, Object> existing(String id, boolean lock) {
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
        exam.remove("edit_history_json");
        Object lastEditedBy = exam.remove("last_edited_by");
        Object lastEditedAt = exam.remove("last_edited_at");
        String editorName = lastEditedBy instanceof Number num
                ? db.queryForList("SELECT full_name FROM users WHERE user_id=?", String.class, num.longValue()).stream().findFirst().orElse(null)
                : null;
        exam.put("last_edited_at", lastEditedAt);
        exam.put("last_edited_by", lastEditedBy == null ? null : VetRows.person(lastEditedBy, editorName));
        var warnings = alerts.evaluate(exam);
        exam.put("alerts", warnings);
        exam.put("suggest_medical_record", !warnings.isEmpty());
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
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize exam history", e);
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read exam history", e);
        }
    }

    private String encodeHistory(List<Map<String, Object>> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize exam history list", e);
        }
    }

    private List<Map<String, Object>> decodeHistory(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read exam history list", e);
        }
    }
}
