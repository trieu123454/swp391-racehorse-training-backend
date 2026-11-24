package com.example.springbootbackend.veterinarian.notification;

import com.example.springbootbackend.veterinarian.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared by all roles; future reminders stay hidden until scheduled_at. */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final JdbcTemplate db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationContent content;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public NotificationService(JdbcTemplate db, VetAccess access, VetAudit audit, VetTime time, NotificationContent content, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db; this.access = access; this.audit = audit; this.time = time; this.content=content; this.json=json;
    }

    /** Compatibility entry point for existing domain services. Never persists the supplied sentence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String schedule(long recipient,String table,String relatedId,String legacyText,Instant scheduledAt) {
        String normalized=java.text.Normalizer.normalize(Objects.toString(legacyText,""),java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('đ','d');
        NotificationContent.Type type=switch(table.toLowerCase(Locale.ROOT)) {
            case "calendar_events", "periodic_care_schedules" -> normalized.contains("qua han") || normalized.contains("overdue") ? NotificationContent.Type.CARE_OVERDUE : normalized.contains("reassign") || normalized.contains("dieu chuyen") ? NotificationContent.Type.CARE_REASSIGNED : NotificationContent.Type.CARE_ASSIGNED;
            case "stable_incidents" -> NotificationContent.Type.INCIDENT_UPDATED;
            case "race_simulations", "training_metrics" -> NotificationContent.Type.MONITORING_ALERT;
            case "training_schedules" -> NotificationContent.Type.TRAINING_UPDATED;
            case "daily_task_logs" -> NotificationContent.Type.TASK_UPDATED;
            case "supply_requests" -> NotificationContent.Type.SUPPLY_UPDATED;
            case "training_videos" -> NotificationContent.Type.VIDEO_UPLOADED;
            case "horse_race_entries" -> NotificationContent.Type.RACE_UPDATED;
            case "horse_registration_requests" -> NotificationContent.Type.REGISTRATION_UPDATED;
            default -> NotificationContent.Type.HEALTH_UPDATED;
        };
        var facts=content.facts(table,relatedId);
        if(table.equalsIgnoreCase("race_simulations")) {
            var matcher=java.util.regex.Pattern.compile("(?i)horse ([0-9a-f-]{36})").matcher(Objects.toString(legacyText,""));
            if(matcher.find()) facts.putAll(content.facts("Horses",matcher.group(1)));
        }
        return schedule(recipient,table,relatedId,type,facts,scheduledAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String schedule(long recipient,String table,String relatedId,NotificationContent.Type type,Map<String,?> params,Instant scheduledAt) {
        String id=UUID.randomUUID().toString();
        var facts=new LinkedHashMap<String,Object>();
        if(params!=null) params.forEach((key,value)->{ if(value!=null) facts.put(key,value); });
        try {
            db.update("INSERT INTO notifications(id,user_id,related_table,related_id,message,is_read,scheduled_at,created_at,type,title,params,link) VALUES (?,?,?,?,?,FALSE,?,?,?,?,?,?)",
                    id,recipient,table,relatedId,"",VetRows.sqlValue(scheduledAt),time.utcNow(),type.name(),type.title,json.writeValueAsString(facts),content.link(recipient,type,table,relatedId,facts));
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Invalid notification facts",failure); }
        return id;
    }

    public Map<String, Object> list(String email, boolean unreadOnly, ApiPage page) {
        long user = access.requireActiveUser(email);
        var now = time.utcNow();
        String where = " WHERE user_id=? AND COALESCE(scheduled_at,created_at)<=?";
        Long unread = db.queryForObject("SELECT count(*) FROM notifications" + where + " AND is_read=FALSE", Long.class, user, now);
        if (unreadOnly) where += " AND is_read=FALSE";
        Long total = db.queryForObject("SELECT count(*) FROM notifications" + where, Long.class, user, now);
        var rows = db.query("SELECT * FROM notifications" + where
                + " ORDER BY CASE WHEN type LIKE 'ACCOUNT_%' OR type LIKE 'ROLE_%' OR lower(related_table)='users' THEN 1 ELSE 0 END, created_at DESC,id DESC LIMIT ? OFFSET ?",
                VetRows.MAPPER, user, now, page.limit(), page.offset());
        rows.forEach(row->{
            Object raw=row.get("params");
            if(raw instanceof String value && !value.isBlank()) {
                try { row.put("params",json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {})); }
                catch(com.fasterxml.jackson.core.JsonProcessingException failure) { row.put("params",Map.of()); }
            }
        });
        return Map.of("data", rows, "total", total, "page", page.page(), "limit", page.limit(), "unread_count", unread);
    }

    @Transactional
    public Map<String, Object> read(String email, String ip, UUID id) {
        long user = access.requireActiveUser(email);
        var rows = db.queryForList("SELECT id FROM notifications WHERE id=? AND user_id=? "
                + "AND COALESCE(scheduled_at,created_at)<=? FOR UPDATE", id.toString(), user, time.utcNow());
        if (rows.isEmpty()) throw new VetException(404, "NOT_FOUND", "Không tìm thấy thông báo");
        db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", id.toString(), user);
        audit.record(user, ip, "READ_NOTIFICATION:" + id);
        return Map.of("id", id, "is_read", true);
    }

    @Transactional
    public Map<String, Object> readAll(String email, String ip) {
        long user = access.requireActiveUser(email);
        int updated = db.update("UPDATE notifications SET is_read=TRUE WHERE user_id=? AND is_read=FALSE "
                + "AND COALESCE(scheduled_at,created_at)<=?", user, time.utcNow());
        audit.record(user, ip, "READ_ALL_NOTIFICATIONS:" + user);
        return Map.of("updated", updated);
    }

    @Transactional
    public void delete(String email,String ip,UUID id) {
        long user=access.requireActiveUser(email);
        int deleted=db.update("DELETE FROM notifications WHERE id=? AND user_id=? AND COALESCE(scheduled_at,created_at)<=?",
                id.toString(),user,time.utcNow());
        if(deleted==0) throw new VetException(404,"NOT_FOUND","Notification not found");
        audit.record(user,ip,"DELETE_NOTIFICATION:"+id);
    }

    public Map<String,Object> target(String email,UUID id) {
        long user=access.requireActiveUser(email);
        var rows=db.queryForList("SELECT related_table,related_id,message,params FROM notifications WHERE id=? AND user_id=? AND COALESCE(scheduled_at,created_at)<=?",
                id.toString(),user,time.utcNow());
        if(rows.isEmpty()) throw new VetException(404,"NOT_FOUND","Notification not found");
        var notification=rows.getFirst(); Object related=notification.get("related_id");
        if(related==null) return Map.of();
        String table=String.valueOf(notification.get("related_table")).toLowerCase(Locale.ROOT);
        if(table.equals("race_simulations")) {
            if(notification.get("params") instanceof String raw && !raw.isBlank()) {
                try {
                    var facts=json.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
                    if(facts.get("horse_id") instanceof String horse) {
                        var participants=db.query("SELECT horse_id FROM race_simulation_horses WHERE simulation_id=? AND horse_id=?",VetRows.MAPPER,related.toString(),horse);
                        return participants.isEmpty()?Map.of():participants.getFirst();
                    }
                } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { /* Historical message fallback below. */ }
            }
            var matcher=java.util.regex.Pattern.compile("(?i)horse ([0-9a-f-]{36})").matcher(String.valueOf(notification.get("message")));
            if(!matcher.find()) return Map.of();
            var participants=db.query("SELECT horse_id FROM race_simulation_horses WHERE simulation_id=? AND horse_id=?",VetRows.MAPPER,related.toString(),matcher.group(1));
            return participants.isEmpty()?Map.of():participants.getFirst();
        }
        String sql=switch(table) {
            case "horses" -> "SELECT id AS horse_id FROM horses WHERE id=? AND deleted_at IS NULL";
            case "periodic_care_schedules" -> "SELECT horse_id,id AS schedule_id,next_due_date AS event_date FROM periodic_care_schedules WHERE id=?";
            case "calendar_events" -> "SELECT horse_id,CASE WHEN source_table='Periodic_Care_Schedules' THEN source_id ELSE NULL END AS schedule_id,event_date FROM calendar_events WHERE id=?";
            case "training_schedules" -> "SELECT ts.horse_id,ce.event_date FROM training_schedules ts LEFT JOIN calendar_events ce ON ce.id=ts.calendar_event_id WHERE ts.id=?";
            case "training_videos" -> "SELECT horse_id FROM training_videos WHERE id=?";
            case "horse_race_entries" -> "SELECT horse_id FROM horse_race_entries WHERE id=?";
            case "daily_task_logs" -> "SELECT horse_id FROM daily_task_logs WHERE id=?";
            case "health_exams" -> "SELECT horse_id FROM health_exams WHERE id=?";
            case "medical_records" -> "SELECT horse_id FROM medical_records WHERE id=?";
            case "injury_markers" -> "SELECT horse_id FROM injury_markers WHERE id=?";
            case "stable_incidents" -> "SELECT horse_id,id AS incident_id FROM stable_incidents WHERE id=?";
            default -> null;
        };
        if(sql==null) return Map.of();
        var targets=db.query(sql,VetRows.MAPPER,related.toString());
        return targets.isEmpty()?Map.of():targets.getFirst();
    }
}
