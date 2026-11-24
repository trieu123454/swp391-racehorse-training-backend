package com.example.springbootbackend.service;

import java.util.*;
import org.springframework.stereotype.Component;

/** Captures domain facts at creation time; presentation sentences belong to the client. */
@Component
public class NotificationContent {
  public enum Type {
    CARE_ASSIGNED("Lịch chăm sóc mới"),
    CARE_REASSIGNED("Thay đổi bác sĩ phụ trách"),
    CARE_OVERDUE("Chăm sóc quá hạn"),
    ROLE_CHANGED("Vai trò đã thay đổi"),
    ROLE_REJECTED("Yêu cầu đổi vai trò bị từ chối"),
    ACCOUNT_APPROVED("Tài khoản đã được phê duyệt"),
    ACCOUNT_REJECTED("Tài khoản bị từ chối"),
    ACCOUNT_LOCKED("Tài khoản bị khóa"),
    ACCOUNT_UNLOCKED("Tài khoản đã được mở khóa"),
    INCIDENT_UPDATED("Cập nhật sự cố"),
    MONITORING_ALERT("Cảnh báo sức khỏe"),
    TRAINING_UPDATED("Cập nhật lịch tập"),
    TASK_UPDATED("Cập nhật ca chăm sóc"),
    HEALTH_UPDATED("Cập nhật sức khỏe ngựa"),
    SUPPLY_UPDATED("Cập nhật yêu cầu vật tư"),
    REGISTRATION_UPDATED("Cập nhật đăng ký ngựa"),
    RACE_UPDATED("Cập nhật đăng ký đua"),
    VIDEO_UPLOADED("Video tập luyện mới");
    public final String title;

    Type(String title) {
      this.title = title;
    }
  }

  private final VetData db;

  public NotificationContent(VetData db) {
    this.db = db;
  }

  public Map<String, Object> facts(String table, String id) {
    if (table == null || id == null) return new LinkedHashMap<>();
    String sql =
        switch (table.toLowerCase(Locale.ROOT)) {
          case "calendar_events" ->
              "SELECT ce.horse_id,h.horse_name,ce.event_date,ce.start_time,ce.title AS"
                  + " care_label,ce.status FROM calendar_events ce JOIN horses h ON"
                  + " h.id=ce.horse_id WHERE ce.id=?";
          case "periodic_care_schedules" ->
              "SELECT p.horse_id,h.horse_name,p.next_due_date AS"
                  + " event_date,p.care_type,p.assigned_doctor_id FROM periodic_care_schedules p"
                  + " JOIN horses h ON h.id=p.horse_id WHERE p.id=?";
          case "stable_incidents" ->
              "SELECT"
                  + " i.horse_id,h.horse_name,i.issue_description,i.status,i.assignment_note,i.result_note,i.assigned_role"
                  + " FROM stable_incidents i JOIN horses h ON h.id=i.horse_id WHERE i.id=?";
          case "training_schedules" ->
              "SELECT t.horse_id,h.horse_name,ce.event_date,ce.start_time,ce.status FROM"
                  + " training_schedules t JOIN horses h ON h.id=t.horse_id LEFT JOIN"
                  + " calendar_events ce ON ce.id=t.calendar_event_id WHERE t.id=?";
          case "daily_task_logs" ->
              "SELECT t.horse_id,h.horse_name,t.task_type,t.status,ce.event_date FROM"
                  + " daily_task_logs t JOIN horses h ON h.id=t.horse_id LEFT JOIN calendar_events"
                  + " ce ON ce.id=t.calendar_event_id WHERE t.id=?";
          case "horses" -> "SELECT id AS horse_id,horse_name,current_status FROM horses WHERE id=?";
          case "health_exams" ->
              "SELECT e.horse_id,h.horse_name FROM health_exams e JOIN horses h ON h.id=e.horse_id"
                  + " WHERE e.id=?";
          case "medical_records" ->
              "SELECT e.horse_id,h.horse_name FROM medical_records e JOIN horses h ON"
                  + " h.id=e.horse_id WHERE e.id=?";
          case "training_videos" ->
              "SELECT e.horse_id,h.horse_name,e.video_description AS description FROM"
                  + " training_schedules e JOIN horses h ON h.id=e.horse_id WHERE e.video_id=?";
          case "horse_race_entries" ->
              "SELECT e.horse_id,h.horse_name FROM horse_race_entries e JOIN horses h ON"
                  + " h.id=e.horse_id WHERE e.id=?";
          case "supply_requests" ->
              "SELECT i.item_name,r.quantity_requested,r.status,r.reason FROM supply_requests r"
                  + " JOIN inventory_items i ON i.id=r.item_id WHERE r.id=?";
          case "horse_registration_requests" ->
              "SELECT horse_name,status,review_note FROM horse_registration_requests WHERE id=?";
          case "training_metrics" ->
              "SELECT e.horse_id,h.horse_name FROM training_metrics_logs e JOIN horses h ON"
                  + " h.id=e.horse_id WHERE e.id=?";
          default -> null;
        };
    if (sql == null) return new LinkedHashMap<>();
    var rows = db.query(sql, VetRows.MAPPER, id);
    return rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.getFirst());
  }

  public String link(
      long recipient, Type type, String table, String id, Map<String, Object> facts) {
    if (table == null || type.name().startsWith("ACCOUNT_") || type.name().startsWith("ROLE_"))
      return null;
    String role =
        db.queryForObject(
            "SELECT r.role_name FROM users u JOIN roles r ON r.role_id=u.role_id WHERE u.user_id=?",
            String.class,
            recipient);
    String slug = role.toLowerCase(Locale.ROOT).replace('_', '-');
    String tab =
        switch (table.toLowerCase(Locale.ROOT)) {
          case "calendar_events", "periodic_care_schedules" ->
              type == Type.CARE_OVERDUE && "HEAD_VETERINARIAN".equals(role) ? "dispatch" : "care";
          case "stable_incidents" -> "HORSE_OWNER".equals(role) ? "health" : "incidents";
          case "training_schedules", "training_videos" ->
              "HORSE_OWNER".equals(role)
                  ? "training"
                  : "HEAD_TRAINER".equals(role) ? "calendar" : "overview";
          case "daily_task_logs" -> "CLUB_MANAGER".equals(role) ? "care" : "overview";
          case "supply_requests" -> "inventory";
          case "horse_registration_requests" -> "horse-requests";
          case "horse_race_entries" -> "races";
          case "health_exams" -> "HORSE_OWNER".equals(role) ? "health" : "exams";
          case "medical_records" -> "HORSE_OWNER".equals(role) ? "health" : "medical";
          case "race_simulations" ->
              "HEAD_TRAINER".equals(role)
                  ? "simulation"
                  : "HORSE_OWNER".equals(role) ? "health" : "overview";
          default -> "overview";
        };
    String target = Objects.toString(facts.get("horse_id"), id);
    if ("horse_registration_requests".equalsIgnoreCase(table) && "HORSE_OWNER".equals(role))
      return "/horses?notificationTarget=" + encode(id);
    String link =
        "/dashboard/"
            + slug
            + "?tab="
            + tab
            + "&notificationTarget="
            + encode("stable_incidents".equalsIgnoreCase(table) ? id : target);
    if (facts.get("horse_id") != null) link += "&horse=" + encode(target);
    if (facts.get("event_date") != null)
      link += "&date=" + encode(facts.get("event_date").toString());
    return link;
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
