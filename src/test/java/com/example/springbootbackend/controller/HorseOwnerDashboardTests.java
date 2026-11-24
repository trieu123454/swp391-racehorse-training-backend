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
}
