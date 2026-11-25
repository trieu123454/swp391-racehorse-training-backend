package com.example.springbootbackend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.security.JwtService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.config.import=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.url=jdbc:h2:mem:owner_dashboard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HorseOwnerDashboardTests {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired AppUserRepository users;
  @Autowired JwtService jwt;
  String horse, token;

  void prepareSchema() {}

  @BeforeEach
  void setup() {
    prepareSchema();
    for (String email : List.of("dashboard@test.com", "other-dashboard@test.com"))
      db.update(
          "INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT"
              + " ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name='HORSE_OWNER'",
          email,
          email);
    db.update(
        "INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT"
            + " 'Trainer','dashboard-trainer@test.com','hash',role_id,'APPROVED' FROM roles WHERE"
            + " role_name='HEAD_TRAINER'");
    horse = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,owner_id) VALUES (?,'Cloud',?)",
        horse,
        users.findByEmailIgnoreCase("dashboard@test.com").orElseThrow().getId());
    token =
        com.example.springbootbackend.TestTokens.create(
            jwt, db, users.findByEmailIgnoreCase("dashboard@test.com").orElseThrow());
  }

  String path() {
    return "/api/horse-owner/horses/" + horse + "/dashboard?year=2026";
  }

  @Test
  void emptyDashboardKeepsAllListsAndZeroFinancialTotals() throws Exception {
    mvc.perform(get(path()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.horse.id").value(horse))
        .andExpect(jsonPath("$.health_exams").isEmpty())
        .andExpect(jsonPath("$.training_metrics").isEmpty())
        .andExpect(jsonPath("$.training_schedules").isEmpty())
        .andExpect(jsonPath("$.training_videos").isEmpty())
        .andExpect(jsonPath("$.race_history").isEmpty())
        .andExpect(jsonPath("$.financial_report.year").value(2026))
        .andExpect(jsonPath("$.financial_report.monthly").isEmpty())
        .andExpect(jsonPath("$.financial_report.transactions").isEmpty())
        .andExpect(jsonPath("$.financial_report.totals.total_expenses").value(0))
        .andExpect(jsonPath("$.financial_report.totals.prize_income").value(0));
    mvc.perform(
            get("/api/notifications?page=1&limit=10").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  void video(String schedule, String id) {
    db.update(
        "UPDATE training_schedules SET video_id=?,video_url='https://example.com/video.mp4' WHERE"
            + " id=?",
        id,
        schedule);
  }

  @Test
  void populatedDashboardKeepsVideoContractNullsAndFinancialYearFilter() throws Exception {
    db.update(
        "INSERT INTO health_exams(id,horse_id) VALUES (?,?)", UUID.randomUUID().toString(), horse);
    db.update(
        "INSERT INTO training_metrics_logs(id,horse_id,avg_speed_kmh) VALUES (?,?,30)",
        UUID.randomUUID().toString(),
        horse);
    String event = UUID.randomUUID().toString(),
        schedule = UUID.randomUUID().toString(),
        video = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,event_date,source_table,source_id)"
            + " VALUES (?,?,'Training','2026-10-01','training_schedules',?)",
        event,
        horse,
        schedule);
    db.update(
        "INSERT INTO training_schedules(id,horse_id,calendar_event_id) VALUES (?,?,?)",
        schedule,
        horse,
        event);
    video(schedule, video);
    String race = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO races(id,race_name,race_date) VALUES (?,'Autumn Cup','2026-10-02')", race);
    String entry = UUID.randomUUID().toString(), raceEvent = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,event_date,source_table,source_id)"
            + " VALUES (?,?,'Race','2026-10-02','horse_race_entries',?)",
        raceEvent,
        horse,
        entry);
    db.update(
        "INSERT INTO horse_race_entries(id,horse_id,race_id,calendar_event_id) VALUES (?,?,?,?)",
        entry,
        horse,
        race,
        raceEvent);
    for (String type : List.of("CARE", "MEDICAL", "PRIZE"))
      db.update(
          "INSERT INTO financial_reports(id,horse_id,transaction_type,amount,billing_period) VALUES"
              + " (?,?,?,10,'2026-10')",
          UUID.randomUUID().toString(),
          horse,
          type);
    db.update(
        "INSERT INTO financial_reports(id,horse_id,transaction_type,amount,billing_period) VALUES"
            + " (?,?,'CARE',999,'2025-12')",
        UUID.randomUUID().toString(),
        horse);
    mvc.perform(get(path()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.health_exams.length()").value(1))
        .andExpect(jsonPath("$.health_exams[0].doctor_name").isEmpty())
        .andExpect(jsonPath("$.training_metrics[0].avg_speed_kmh").value(30))
        .andExpect(jsonPath("$.training_schedules.length()").value(1))
        .andExpect(jsonPath("$.race_history.length()").value(1))
        .andExpect(jsonPath("$.training_videos[0].id").value(video))
        .andExpect(jsonPath("$.training_videos[0].training_schedule_id").value(schedule))
        .andExpect(
            jsonPath("$.training_videos[0].video_url").value("https://example.com/video.mp4"))
        .andExpect(jsonPath("$.training_videos[0].uploaded_by_name").isEmpty())
        .andExpect(jsonPath("$.financial_report.transactions.length()").value(3))
        .andExpect(jsonPath("$.financial_report.totals.total_expenses").value(20))
        .andExpect(jsonPath("$.financial_report.totals.prize_income").value(10));
  }

  @Test
  void ownershipRoleAndAccountRestrictionsRemainEnforced() throws Exception {
    String other =
        com.example.springbootbackend.TestTokens.create(
            jwt, db, users.findByEmailIgnoreCase("other-dashboard@test.com").orElseThrow());
    String trainer =
        com.example.springbootbackend.TestTokens.create(
            jwt, db, users.findByEmailIgnoreCase("dashboard-trainer@test.com").orElseThrow());
    mvc.perform(get(path()).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    mvc.perform(get(path()).header("Authorization", "Bearer " + trainer))
        .andExpect(status().isForbidden());
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/horse-owner/horses/" + UUID.randomUUID() + "/dashboard?year=2026")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    db.update("UPDATE users SET must_change_password=TRUE WHERE email='dashboard@test.com'");
    entityManager.clear();
    mvc.perform(get(path()).header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void careAndTrainingSchedulesIncludeHistoryAndEnforceOwnerIsolation() throws Exception {
    long otherOwnerId = users.findByEmailIgnoreCase("other-dashboard@test.com").orElseThrow().getId();
    String otherHorse = UUID.randomUUID().toString();
    db.update("INSERT INTO horses(id,horse_name,owner_id) VALUES (?,'OtherHorse',?)", otherHorse, otherOwnerId);

    // Other owner's training & care schedules must never leak
    String otherTrainEvent = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Training','Training','2026-11-25','07:00:00','08:30:00','Scheduled','training_schedules',?)",
        otherTrainEvent,
        otherHorse,
        UUID.randomUUID().toString());
    String otherCareRule = UUID.randomUUID().toString();
    String otherCareEvent = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Vaccination','Vaccination','2026-11-25','08:45:00','10:15:00','Scheduled','Periodic_Care_Schedules',?)",
        otherCareEvent,
        otherHorse,
        otherCareRule);
    db.update(
        "INSERT INTO periodic_care_schedules(id,horse_id,care_type,frequency_days,next_due_date,calendar_event_id)"
            + " VALUES (?,?,'Vaccination',30,'2026-11-25',?)",
        otherCareRule,
        otherHorse,
        otherCareEvent);

    // Current owner's horse: completed + scheduled + cancelled + unbooked due care rule
    String ruleId = UUID.randomUUID().toString();
    String scheduledCareEvent = UUID.randomUUID().toString();
    String completedCareEvent = UUID.randomUUID().toString();
    String cancelledCareEvent = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO periodic_care_schedules(id,horse_id,care_type,frequency_days,next_due_date)"
            + " VALUES (?,?,'HoofCheck',30,'2026-12-25')",
        ruleId,
        horse);
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'HoofCheck','HoofCheck','2026-12-25','10:30:00','12:00:00','Scheduled','Periodic_Care_Schedules',?)",
        scheduledCareEvent,
        horse,
        ruleId);
    db.update(
        "UPDATE periodic_care_schedules SET calendar_event_id=? WHERE id=?",
        scheduledCareEvent,
        ruleId);
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'HoofCheck','HoofCheck','2026-11-25','08:45:00','10:15:00','Completed','Periodic_Care_Schedules',?)",
        completedCareEvent,
        horse,
        ruleId);
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'HoofCheck','HoofCheck','2026-11-20','07:00:00','08:30:00','Cancelled','Periodic_Care_Schedules',?)",
        cancelledCareEvent,
        horse,
        ruleId);

    // Unbooked periodic care rule due on 2026-11-28
    db.update(
        "INSERT INTO periodic_care_schedules(id,horse_id,care_type,frequency_days,next_due_date,calendar_event_id)"
            + " VALUES (?,?,'MedicalCheckup',14,'2026-11-28',NULL)",
        UUID.randomUUID().toString(),
        horse);

    // Training schedule with mixed-case source_table
    String trainSchedule = UUID.randomUUID().toString();
    String trainEvent = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Training','Training','2026-11-25','12:30:00','14:00:00','Scheduled','Training_Schedules',?)",
        trainEvent,
        horse,
        trainSchedule);
    db.update(
        "INSERT INTO training_schedules(id,horse_id,calendar_event_id,session_type,track_surface)"
            + " VALUES (?,?,?,'Training','Dirt')",
        trainSchedule,
        horse,
        trainEvent);

    mvc.perform(get(path()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.care_schedules.length()").value(3))
        .andExpect(jsonPath("$.care_schedules[0].event_date").value("2026-12-25"))
        .andExpect(jsonPath("$.care_schedules[1].event_date").value("2026-11-28"))
        .andExpect(jsonPath("$.care_schedules[1].is_periodic_due").value(true))
        .andExpect(jsonPath("$.care_schedules[2].event_date").value("2026-11-25"))
        .andExpect(jsonPath("$.care_schedules[2].status").value("Completed"))
        .andExpect(jsonPath("$.training_schedules.length()").value(1))
        .andExpect(jsonPath("$.training_schedules[0].id").value(trainEvent));

    mvc.perform(
            get("/api/horse-owner/horses/" + otherHorse + "/dashboard?year=2026")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void ownerCalendarReturnsAllOwnedHorsesSchedulesWithoutCrossOwnerLeak() throws Exception {
    long ownerId = users.findByEmailIgnoreCase("dashboard@test.com").orElseThrow().getId();
    long otherOwnerId = users.findByEmailIgnoreCase("other-dashboard@test.com").orElseThrow().getId();
    String boxA = UUID.randomUUID().toString();
    String boxB = UUID.randomUUID().toString();
    db.update("INSERT INTO stable_boxes(id,box_code,section,capacity) VALUES (?,'T-A04','Stable A',1)", boxA);
    db.update("INSERT INTO stable_boxes(id,box_code,section,capacity) VALUES (?,'T-B03','Stable B',1)", boxB);
    db.update("UPDATE horses SET stable_box_id=? WHERE id=?", boxA, horse);

    String horse2 = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,owner_id,stable_box_id) VALUES (?,'Quarter Horse',?,?)",
        horse2,
        ownerId,
        boxB);

    String otherHorse = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,owner_id) VALUES (?,'Foreign Horse',?)",
        otherHorse,
        otherOwnerId);

    String planId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO training_plans(id,horse_id,stage_name,start_date,end_date) VALUES (?,?,'Liệu trình VỪA','2026-11-01','2026-11-30')",
        planId,
        horse);
    String ts1 = UUID.randomUUID().toString(), ev1 = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Training','Training','2026-11-25','10:30:00','12:00:00','Scheduled','training_schedules',?)",
        ev1,
        horse,
        ts1);
    db.update(
        "INSERT INTO training_schedules(id,horse_id,calendar_event_id,training_plan_id,session_type,track_surface,target_distance_meters)"
            + " VALUES (?,?,?,?,'Training','Dirt',1200)",
        ts1,
        horse,
        ev1,
        planId);

    String ts2 = UUID.randomUUID().toString(), ev2 = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Training','Training','2026-11-25','12:30:00','14:00:00','Scheduled','training_schedules',?)",
        ev2,
        horse2,
        ts2);
    db.update(
        "INSERT INTO training_schedules(id,horse_id,calendar_event_id,session_type,track_surface)"
            + " VALUES (?,?,?,'Training','Turf')",
        ts2,
        horse2,
        ev2);

    String foreignEv = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id)"
            + " VALUES (?,?,'Training','Training','2026-11-25','14:15:00','15:45:00','Scheduled','training_schedules',?)",
        foreignEv,
        otherHorse,
        UUID.randomUUID().toString());

    db.update(
        "INSERT INTO periodic_care_schedules(id,horse_id,care_type,frequency_days,next_due_date,calendar_event_id)"
            + " VALUES (?,?,'HoofCheck',30,'2026-11-26',NULL)",
        UUID.randomUUID().toString(),
        horse2);
    db.update(
        "INSERT INTO periodic_care_schedules(id,horse_id,care_type,frequency_days,next_due_date,calendar_event_id)"
            + " VALUES (?,?,'Vaccination',30,'2026-11-26',NULL)",
        UUID.randomUUID().toString(),
        otherHorse);

    mvc.perform(get("/api/horse-owner/calendar").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.training_schedules.length()").value(2))
        .andExpect(jsonPath("$.training_schedules[?(@.horse_name=='Cloud')].stage_name").value("Liệu trình VỪA"))
        .andExpect(jsonPath("$.training_schedules[?(@.horse_name=='Cloud')].box_code").value("T-A04"))
        .andExpect(jsonPath("$.training_schedules[?(@.horse_name=='Quarter Horse')].box_code").value("T-B03"))
        .andExpect(jsonPath("$.care_schedules.length()").value(1))
        .andExpect(jsonPath("$.care_schedules[0].horse_name").value("Quarter Horse"));

    mvc.perform(
            get("/api/horse-owner/calendar?horseId=" + otherHorse)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }
}
