package com.example.springbootbackend.repository;

import com.example.springbootbackend.security.JwtAuthenticationFilter;
import com.example.springbootbackend.service.CalendarEventSources;
import com.example.springbootbackend.service.VetData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/** Read queries for the Horse Owner portal. Horse ownership is part of every horse query. */
@Repository
public class HorseOwnerRepository {
  private static final String HORSE_SELECT =
      "SELECT h.id,h.horse_name,h.breed,h.gender,h.birth_year,h.pedigree_father,h.pedigree_mother,"
          + "h.microchip_number,h.passport_number,"
          + "h.image_url,h.current_status,h.current_weight_kg,h.height_cm,h.readiness_status,"
          + "h.is_training_locked,h.lock_level,h.lock_reason,h.created_at,"
          + "h.stable_box_id,h.owner_id,s.box_code,s.section,u.full_name AS owner_name "
          + "FROM horses h "
          + "LEFT JOIN stable_boxes s ON s.id=h.stable_box_id "
          + "LEFT JOIN users u ON u.user_id=h.owner_id ";
  private final VetData db;
  private volatile Boolean consolidatedVideos;

  public HorseOwnerRepository(VetData db) {
    this.db = db;
  }

  public Long findApprovedOwnerId(String email) {
    JwtAuthenticationFilter.VerifiedActor verified = JwtAuthenticationFilter.currentActor(email);
    if (verified != null) {
      return "HORSE_OWNER".equals(verified.role()) ? verified.userId() : null;
    }
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT u.user_id "
                + "FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE lower(u.email)=lower(?) AND u.status='APPROVED' "
                + "AND u.deleted_at IS NULL AND u.must_change_password=FALSE "
                + "AND r.role_name='HORSE_OWNER'",
            email);
    return rows.isEmpty() ? null : ((Number) rows.getFirst().get("user_id")).longValue();
  }

  public List<Map<String, Object>> findHorses(long ownerId) {
    return db.queryForList(
        HORSE_SELECT + "WHERE h.owner_id=? AND h.deleted_at IS NULL ORDER BY h.horse_name,h.id",
        ownerId);
  }

  public Optional<Map<String, Object>> findOwnedHorse(String horseId, long ownerId) {
    List<Map<String, Object>> horses =
        db.queryForList(
            HORSE_SELECT + "WHERE h.id=? AND h.owner_id=? AND h.deleted_at IS NULL",
            horseId,
            ownerId);
    return horses.stream().findFirst();
  }

  public List<Map<String, Object>> findHealthExams(String horseId) {
    return db.queryForList(
        "SELECT e.id,e.exam_date,e.temperature_c,e.heart_rate,e.respiratory_rate,"
            + "e.body_condition_score,e.gait_assessment,u.full_name AS doctor_name "
            + "FROM health_exams e LEFT JOIN users u ON u.user_id=e.doctor_id "
            + "WHERE e.horse_id=? ORDER BY e.exam_date DESC,e.id DESC",
        horseId);
  }

  public List<Map<String, Object>> findTrainingMetrics(String horseId) {
    return db.queryForList(
        "SELECT m.id,m.training_schedule_id,m.body_weight_kg,m.max_heart_rate,"
            + "m.avg_speed_kmh,m.stamina_score,m.has_injury_alert,m.trainer_review,"
            + "m.is_simulated,m.recorded_at,u.full_name AS recorded_by_name "
            + "FROM shared_training_metrics m "
            + "LEFT JOIN users u ON u.user_id=m.recorded_by "
            + "WHERE m.horse_id=? "
            + "ORDER BY m.recorded_at DESC,m.id DESC",
        horseId);
  }

  public List<Map<String, Object>> findCareSchedules(String horseId, long ownerId) {
    return findCareSchedulesInternal(ownerId, horseId);
  }

  public List<Map<String, Object>> findAllCareSchedulesForOwner(long ownerId) {
    return findCareSchedulesInternal(ownerId, null);
  }

  private List<Map<String, Object>> findCareSchedulesInternal(long ownerId, String horseId) {
    List<Object> eventArgs = new ArrayList<>();
    eventArgs.add(ownerId);
    String eventHorseFilter = "";
    if (horseId != null) {
      eventHorseFilter = "AND ce.horse_id=? ";
      eventArgs.add(horseId);
    }
    List<Map<String, Object>> bookedEvents =
        db.queryForList(
            "SELECT ce.id,COALESCE(p.care_type,ce.event_type) AS care_type,"
                + "p.frequency_days,p.last_done_date,p.next_due_date,p.notes,"
                + "ce.id AS calendar_event_id,ce.event_date,ce.start_time,ce.end_time,"
                + "COALESCE(ce.status,'Scheduled') AS status,ce.title,"
                + "u.full_name AS doctor_name,p.id AS schedule_id,"
                + "h.id AS horse_id,h.horse_name,sb.box_code,sb.section "
                + "FROM calendar_events ce "
                + "JOIN horses h ON h.id=ce.horse_id AND h.owner_id=? AND h.deleted_at IS NULL "
                + "LEFT JOIN stable_boxes sb ON sb.id=h.stable_box_id "
                + "LEFT JOIN periodic_care_schedules p ON p.id=ce.source_id "
                + "LEFT JOIN users u ON u.user_id=p.assigned_doctor_id "
                + "WHERE (LOWER(ce.source_table)='periodic_care_schedules' "
                + "OR ce.event_type IN ('HoofCheck','Deworming','Vaccination','MedicalCheckup','VetCheckup')) "
                + eventHorseFilter
                + "AND COALESCE(ce.status,'Scheduled')<>'Cancelled' "
                + "ORDER BY ce.event_date DESC,ce.start_time DESC NULLS LAST,ce.id DESC",
            eventArgs.toArray());

    List<Object> ruleArgs = new ArrayList<>();
    ruleArgs.add(ownerId);
    String ruleHorseFilter = "";
    if (horseId != null) {
      ruleHorseFilter = "WHERE p.horse_id=? ";
      ruleArgs.add(horseId);
    }
    List<Map<String, Object>> periodicRules =
        db.queryForList(
            "SELECT p.id,p.care_type,p.frequency_days,p.last_done_date,p.next_due_date,"
                + "p.notes,p.calendar_event_id,ce.status AS event_status,"
                + "u.full_name AS doctor_name,"
                + "h.id AS horse_id,h.horse_name,sb.box_code,sb.section "
                + "FROM periodic_care_schedules p "
                + "JOIN horses h ON h.id=p.horse_id AND h.owner_id=? AND h.deleted_at IS NULL "
                + "LEFT JOIN stable_boxes sb ON sb.id=h.stable_box_id "
                + "LEFT JOIN calendar_events ce ON ce.id=p.calendar_event_id "
                + "LEFT JOIN users u ON u.user_id=p.assigned_doctor_id "
                + ruleHorseFilter
                + "ORDER BY p.next_due_date DESC,p.id DESC",
            ruleArgs.toArray());

    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> bookedRuleDates = new HashSet<>();
    for (Map<String, Object> row : bookedEvents) {
      Map<String, Object> item = new LinkedHashMap<>(row);
      String scheduleId = Objects.toString(item.remove("schedule_id"), "");
      String dateKey = dateKey(item.get("event_date"));
      if (!scheduleId.isEmpty() && !dateKey.isEmpty()) {
        bookedRuleDates.add(scheduleId + "|" + dateKey);
      }
      item.put("is_periodic_due", false);
      result.add(item);
    }

    for (Map<String, Object> rule : periodicRules) {
      Object nextDueDate = rule.get("next_due_date");
      String dueKey = dateKey(nextDueDate);
      if (dueKey.isEmpty()) continue;

      String evStatus = Objects.toString(rule.get("event_status"), "");
      if (rule.get("frequency_days") == null && "Completed".equals(evStatus)) continue;
      if (rule.get("calendar_event_id") != null && "Scheduled".equals(evStatus)) continue;

      String ruleId = Objects.toString(rule.get("id"), "");
      if (bookedRuleDates.contains(ruleId + "|" + dueKey)) continue;

      Map<String, Object> dueItem = new LinkedHashMap<>();
      dueItem.put("id", "periodic-" + ruleId);
      dueItem.put("care_type", rule.get("care_type"));
      dueItem.put("frequency_days", rule.get("frequency_days"));
      dueItem.put("last_done_date", rule.get("last_done_date"));
      dueItem.put("next_due_date", nextDueDate);
      dueItem.put("notes", rule.get("notes"));
      dueItem.put("calendar_event_id", null);
      dueItem.put("event_date", nextDueDate);
      dueItem.put("start_time", null);
      dueItem.put("end_time", null);
      dueItem.put("status", "Scheduled");
      dueItem.put("title", rule.get("care_type"));
      dueItem.put("doctor_name", rule.get("doctor_name"));
      dueItem.put("horse_id", rule.get("horse_id"));
      dueItem.put("horse_name", rule.get("horse_name"));
      dueItem.put("box_code", rule.get("box_code"));
      dueItem.put("section", rule.get("section"));
      dueItem.put("is_periodic_due", true);
      result.add(dueItem);
    }

    result.sort(
        (a, b) -> {
          int dateCmp = dateKey(b.get("event_date")).compareTo(dateKey(a.get("event_date")));
          if (dateCmp != 0) return dateCmp;
          String aTime = Objects.toString(a.get("start_time"), "");
          String bTime = Objects.toString(b.get("start_time"), "");
          if (aTime.isEmpty() != bTime.isEmpty()) return aTime.isEmpty() ? 1 : -1;
          int timeCmp = bTime.compareTo(aTime);
          if (timeCmp != 0) return timeCmp;
          return Objects.toString(b.get("id"), "").compareTo(Objects.toString(a.get("id"), ""));
        });
    return result;
  }

  private static String dateKey(Object value) {
    String raw = Objects.toString(value, "");
    return raw.length() >= 10 ? raw.substring(0, 10) : raw;
  }

  public List<Map<String, Object>> findTrainingSchedules(String horseId, long ownerId) {
    return findTrainingSchedulesInternal(ownerId, horseId);
  }

  public List<Map<String, Object>> findAllTrainingSchedulesForOwner(long ownerId) {
    return findTrainingSchedulesInternal(ownerId, null);
  }

  private List<Map<String, Object>> findTrainingSchedulesInternal(long ownerId, String horseId) {
    List<Object> args = new ArrayList<>();
    args.add(ownerId);
    args.add(CalendarEventSources.TRAINING_SCHEDULES);
    String horseFilter = "";
    if (horseId != null) {
      horseFilter = "AND ce.horse_id=? ";
      args.add(horseId);
    }
    return db.queryForList(
        "SELECT ce.id,ce.event_type,ce.title,ce.event_date,ce.start_time,ce.end_time,"
            + "ce.status,ts.id AS training_schedule_id,ts.session_type,ts.track_surface,"
            + "ts.target_distance_meters,ts.target_intensity,"
            + "COALESCE(ts.snapshot_stage_name,tp.stage_name) AS stage_name,"
            + "g.full_name AS assigned_groom_name,"
            + "h.id AS horse_id,h.horse_name,sb.box_code,sb.section "
            + "FROM calendar_events ce "
            + "JOIN horses h ON h.id=ce.horse_id AND h.owner_id=? AND h.deleted_at IS NULL "
            + "LEFT JOIN stable_boxes sb ON sb.id=h.stable_box_id "
            + "LEFT JOIN training_schedules ts ON (ts.id=ce.source_id OR ts.calendar_event_id=ce.id) "
            + "LEFT JOIN training_plans tp ON tp.id=ts.training_plan_id "
            + "LEFT JOIN users g ON g.user_id=ts.assigned_groom_id "
            + "WHERE LOWER(ce.source_table)=? "
            + horseFilter
            + "ORDER BY ce.event_date DESC,ce.start_time DESC NULLS LAST,ce.id DESC",
        args.toArray());
  }

  public List<Map<String, Object>> findTrainingVideos(String horseId) {
    Boolean cached = consolidatedVideos;
    if (cached == null) {
      Integer count =
          db.queryForObject(
              "SELECT count(*) FROM information_schema.columns "
                  + "WHERE table_schema=current_schema() AND table_name='training_schedules' "
                  + "AND column_name='video_url'",
              Integer.class);
      cached = count != null && count > 0;
      consolidatedVideos = cached;
    }
    if (cached) {
      return db.queryForList(
          "SELECT ts.video_id AS id,ts.id AS training_schedule_id,ts.video_url,"
              + "ts.video_description AS description,ts.video_uploaded_at AS uploaded_at,"
              + "u.full_name AS uploaded_by_name,ts.session_type "
              + "FROM training_schedules ts "
              + "LEFT JOIN users u ON u.user_id=ts.video_uploaded_by "
              + "WHERE ts.horse_id=? AND ts.video_url IS NOT NULL AND TRIM(ts.video_url)<>'' "
              + "ORDER BY ts.video_uploaded_at DESC NULLS LAST,ts.video_id DESC,ts.id DESC",
          horseId);
    }
    return db.queryForList(
        "SELECT COALESCE(ts.video_id, ts.id) AS id,"
            + "ts.id AS training_schedule_id,"
            + "ts.video_url,"
            + "ts.video_description AS description,"
            + "ts.video_uploaded_at AS uploaded_at,"
            + "u.full_name AS uploaded_by_name,"
            + "ts.session_type "
            + "FROM training_schedules ts "
            + "LEFT JOIN users u ON u.user_id=ts.video_uploaded_by "
            + "WHERE ts.horse_id=? AND ts.video_url IS NOT NULL "
            + "ORDER BY ts.video_uploaded_at DESC,ts.id DESC",
        horseId);
  }

  public List<Map<String, Object>> findRaceHistory(String horseId) {
    return db.queryForList(
        "SELECT e.id AS entry_id,e.status AS"
            + " entry_status,e.cancel_reason,e.result_position,e.prize_amount,e.created_at,r.id AS"
            + " race_id,r.race_name,r.grade,r.distance_category,"
            + "r.distance_meters,r.race_date,r.location,r.description,r.is_simulated,COALESCE(r.status,'Scheduled')"
            + " AS race_status FROM horse_race_entries e JOIN races r ON r.id=e.race_id WHERE"
            + " e.horse_id=? ORDER BY r.race_date DESC,e.created_at DESC,e.id DESC",
        horseId);
  }

  public List<Map<String, Object>> findFinancialTransactions(String horseId, int year) {
    return db.queryForList(
        "SELECT id,transaction_type,amount,billing_period,created_at "
            + "FROM financial_reports "
            + "WHERE horse_id=? AND billing_period>=? AND billing_period<=? "
            + "ORDER BY billing_period DESC,created_at DESC,id DESC",
        horseId,
        year + "-01",
        year + "-12");
  }
}
