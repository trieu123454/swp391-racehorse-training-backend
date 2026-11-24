package com.example.springbootbackend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
      "spring.datasource.url=jdbc:h2:mem:club_manager_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClubManagerControllerTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  @BeforeEach
  void setup() {
    createUser("Club Manager", "manager@test.com", "CLUB_MANAGER", "APPROVED");
    createUser("Pending Trainer", "trainer@test.com", "HEAD_TRAINER", "PENDING");
    createUser("Pending Owner", "owner@test.com", "HORSE_OWNER", "PENDING");
    createUser("Approved Groom", "groom@test.com", "GROOM", "APPROVED");
    createUser("Locked Groom", "locked@test.com", "GROOM", "LOCKED");
  }

  @Test
  void inventoryTransactionCatalogAndAllSupplyRequestsRespectContractAndPermissions()
      throws Exception {
    String item = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO inventory_items(id,item_name,item_code,reorder_threshold) VALUES"
            + " (?,'Hay','HAY',0)",
        item);
    long actor = userId("manager@test.com"), groom = userId("groom@test.com");
    String path = "/api/club-manager/inventory-transactions";
    mvc.perform(get(path).with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
    db.update(
        "INSERT INTO"
            + " inventory_transactions(id,item_id,actor_id,transaction_type,quantity_change,quantity_before,quantity_after,created_at)"
            + " VALUES (?,?,?,'RECEIPT',10,0,10,CURRENT_TIMESTAMP)",
        java.util.UUID.randomUUID().toString(),
        item,
        actor);
    mvc.perform(get(path + "?limit=100").with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].item_code").value("HAY"))
        .andExpect(jsonPath("$.data[0].quantity_after").value(10))
        .andExpect(jsonPath("$.data[0].lot_number").isEmpty());
    mvc.perform(get(path + "?limit=0").with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path).with(user("groom@test.com").roles("GROOM")))
        .andExpect(status().isForbidden());
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    for (String state : java.util.List.of("Pending", "Approved", "Rejected", "PartiallyApproved"))
      db.update(
          "INSERT INTO"
              + " supply_requests(id,requested_by,item_id,quantity_requested,status,created_at)"
              + " VALUES (?,?,?,1,?,CURRENT_TIMESTAMP)",
          java.util.UUID.randomUUID().toString(),
          groom,
          item,
          state);
    mvc.perform(
            get("/api/club-manager/supply-requests?status=All")
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4));
    mvc.perform(
            get("/api/club-manager/supply-requests?status=Pending")
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    mvc.perform(
            get("/api/club-manager/supply-requests?status=invalid")
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void pendingListExcludesHorseOwnersAndSupportsRoleFilter() throws Exception {
    mvc.perform(
            get("/api/club-manager/users/pending")
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .param("role", "HEAD_TRAINER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.data[0].role_name").value("HEAD_TRAINER"))
        .andExpect(jsonPath("$.data[0].status").value("PENDING"));
  }

  @Test
  void approveCreatesNotificationAndAuditAndCannotBeRepeated() throws Exception {
    long trainerId = userId("trainer@test.com");
    mvc.perform(
            patch("/api/club-manager/users/{id}/approve", trainerId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=?", Long.class, trainerId))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action_performed=?",
                Long.class,
                "APPROVE_USER: " + trainerId))
        .isEqualTo(1);
    mvc.perform(
            patch("/api/club-manager/users/{id}/approve", trainerId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isConflict());
  }

  @Test
  void managerCannotLockSelfOrAnotherManager() throws Exception {
    long managerId = userId("manager@test.com");
    mvc.perform(
            patch("/api/club-manager/users/{id}/lock", managerId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "test"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void lockedAccountsRemainListedAndCanBeUnlocked() throws Exception {
    long lockedGroomId = userId("locked@test.com");
    mvc.perform(
            get("/api/club-manager/users")
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .param("status", "LOCKED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(lockedGroomId))
        .andExpect(jsonPath("$[0].status").value("LOCKED"));
    mvc.perform(
            patch("/api/club-manager/users/{id}/unlock", lockedGroomId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    assertThat(
            db.queryForObject(
                "SELECT status FROM users WHERE user_id=?", String.class, lockedGroomId))
        .isEqualTo("APPROVED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=?", Long.class, lockedGroomId))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action_performed=?",
                Long.class,
                "UNLOCK_USER: " + lockedGroomId))
        .isEqualTo(1);
    mvc.perform(
            patch("/api/club-manager/users/{id}/unlock", lockedGroomId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isConflict());
  }

  @Test
  void managerCannotUnlockAnotherClubManager() throws Exception {
    createUser("Locked Manager", "locked-manager@test.com", "CLUB_MANAGER", "LOCKED");
    long managerId = userId("locked-manager@test.com");
    mvc.perform(
            patch("/api/club-manager/users/{id}/unlock", managerId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createStaffAccountCreatesUserAndSendsNotifications() throws Exception {
    mvc.perform(
            post("/api/club-manager/users")
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "fullName", "Nguyễn thị Mò",
                            "email", "indigo758@fldubai.com",
                            "phone", "0325658292",
                            "password", "Abcd123456",
                            "roleName", "GROOM"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("indigo758@fldubai.com"))
        .andExpect(jsonPath("$.role_name").value("GROOM"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.must_change_password").value(true));
    long newUserId = userId("indigo758@fldubai.com");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=?", Long.class, newUserId))
        .isEqualTo(1);
  }

  @Test
  void officialRaceLifecycleRescheduleConflictCheckAndCancelCascade() throws Exception {
    createUser("Trainer Active", "trainer-active@test.com", "HEAD_TRAINER", "APPROVED");
    createUser("Owner Active", "owner-active@test.com", "HORSE_OWNER", "APPROVED");
    long trainerId = userId("trainer-active@test.com");
    long ownerId = userId("owner-active@test.com");
    LocalDate today = LocalDate.now(com.example.springbootbackend.service.VetTime.BUSINESS_ZONE);
    LocalDate initialDate = today.plusDays(10);
    LocalDate conflictDate = today.plusDays(12);
    LocalDate cleanDate = today.plusDays(15);
    String horseId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,owner_id,current_status,readiness_status) VALUES"
            + " (?,?,?,'Healthy','Ready')",
        horseId,
        "Starlight",
        ownerId);
    String createResponse =
        mvc.perform(
                post("/api/club-manager/races")
                    .with(user("manager@test.com").roles("CLUB_MANAGER"))
                    .contentType(APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "race_name", "Grand Derby",
                                "race_date", initialDate.toString(),
                                "distance_meters", 1600,
                                "grade", "G1",
                                "location", "Saigon Turf"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Scheduled"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String raceId = json.readTree(createResponse).get("id").asText();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND related_table='Races'",
                Long.class,
                trainerId))
        .isEqualTo(1);
    // Duplicate name + date rejected
    mvc.perform(
            post("/api/club-manager/races")
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("race_name", " grand derby ", "race_date", initialDate.toString()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_RACE"));
    // Register horse via Head Trainer endpoint
    String regRes =
        mvc.perform(
                post("/api/horses/" + horseId + "/race-entries")
                    .with(user("trainer-active@test.com").roles("HEAD_TRAINER"))
                    .contentType(APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("race_id", raceId))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String entryId = json.readTree(regRes).get("entry_id").asText();
    // Create a scheduled event for Starlight on conflictDate
    String conflictEventId = UUID.randomUUID().toString();
    String trainingId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id,created_by)"
            + " VALUES"
            + " (?,?,'Training','Morning',?,'07:00:00','08:00:00','Scheduled','training_schedules',?,?)",
        conflictEventId,
        horseId,
        conflictDate,
        trainingId,
        trainerId);
    // Rescheduling race to conflictDate fails cleanly
    mvc.perform(
            patch("/api/club-manager/races/{raceId}", raceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("race_date", conflictDate.toString()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SCHEDULE_CONFLICT"));
    assertThat(db.queryForObject("SELECT race_date FROM races WHERE id=?", LocalDate.class, raceId))
        .isEqualTo(initialDate);
    // Rescheduling race to cleanDate updates both race and calendar_events
    mvc.perform(
            patch("/api/club-manager/races/{raceId}", raceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "race_date",
                            cleanDate.toString(),
                            "race_name",
                            "Grand Derby Updated"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.race_date").value(cleanDate.toString()))
        .andExpect(jsonPath("$.registered_count").value(1));
    assertThat(
            db.queryForObject(
                "SELECT ce.event_date FROM calendar_events ce JOIN horse_race_entries e ON"
                    + " e.calendar_event_id=ce.id WHERE e.id=?",
                LocalDate.class,
                entryId))
        .isEqualTo(cleanDate);
    // Manager views registered horses
    mvc.perform(
            get("/api/club-manager/races/{raceId}/entries", raceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].horse_name").value("Starlight"));
    // Cancel race cascades Cancelled to entry & calendar_event
    mvc.perform(
            post("/api/club-manager/races/{raceId}/cancel", raceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "Thời tiết xấu"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Cancelled"))
        .andExpect(jsonPath("$.cancelled_count").value(1));
    assertThat(
            db.queryForObject(
                "SELECT status FROM horse_race_entries WHERE id=?", String.class, entryId))
        .isEqualTo("Cancelled");
    assertThat(
            db.queryForObject(
                "SELECT ce.status FROM calendar_events ce JOIN horse_race_entries e ON"
                    + " e.calendar_event_id=ce.id WHERE e.id=?",
                String.class,
                entryId))
        .isEqualTo("Cancelled");
  }

  @Test
  void competitionReportExcludesSimulatedAndCompletedRaceLocksDateEditAndCancel() throws Exception {
    LocalDate today = LocalDate.now(com.example.springbootbackend.service.VetTime.BUSINESS_ZONE);
    String horseId = UUID.randomUUID().toString();
    String officialRaceId = UUID.randomUUID().toString();
    String simRaceId = UUID.randomUUID().toString();
    String event1 = UUID.randomUUID().toString();
    String event2 = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,current_status,readiness_status) VALUES"
            + " (?,'Champion','Healthy','Ready')",
        horseId);
    db.update(
        "INSERT INTO races(id,race_name,race_date,is_simulated,status) VALUES "
            + "(?,'Official Classic',?,FALSE,'Completed'),(?,'Sim Run',?,TRUE,'Completed')",
        officialRaceId,
        today,
        simRaceId,
        today);
    db.update(
        "INSERT INTO"
            + " calendar_events(id,horse_id,event_type,title,event_date,status,source_table,source_id)"
            + " VALUES (?,?,'Race','Official Classic',?,'Completed','horse_race_entries',?),"
            + " (?,?,'Race','Sim Run',?,'Completed','horse_race_entries',?)",
        event1,
        horseId,
        today,
        event1,
        event2,
        horseId,
        today,
        event2);
    db.update(
        "INSERT INTO"
            + " horse_race_entries(id,calendar_event_id,horse_id,race_id,result_position,prize_amount,status)"
            + " VALUES (?,?,?,?,1,12000000,'Completed'),(?,?,?,?,1,99999999,'Completed')",
        event1,
        event1,
        horseId,
        officialRaceId,
        event2,
        event2,
        horseId,
        simRaceId);
    mvc.perform(
            patch("/api/club-manager/races/{raceId}", officialRaceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("race_date", today.plusDays(3).toString()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("RACE_HAS_COMPLETED_RESULTS"));
    mvc.perform(
            post("/api/club-manager/races/{raceId}/cancel", officialRaceId)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "Test"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("RACE_HAS_COMPLETED_RESULTS"));
    mvc.perform(
            get("/api/club-manager/competition-report")
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.completed").value(1))
        .andExpect(jsonPath("$.summary.wins").value(1))
        .andExpect(jsonPath("$.leaderboard.length()").value(1))
        .andExpect(jsonPath("$.leaderboard[0].total_prize").value(12000000));
  }

  @Test
  void malformedPathAndUnauthorizedRoleReturn400And403InsteadOf500() throws Exception {
    mvc.perform(
            patch("/api/club-manager/groom-tasks/not-a-uuid")
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "Cancel"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").exists());
    mvc.perform(get("/api/club-manager/users").with(user("groom@test.com").roles("GROOM")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").exists());
  }

  private void createUser(String name, String email, String role, String status) {
    Integer roleId =
        db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, role);
    db.update(
        "INSERT INTO users(full_name,email,password_hash,role_id,status) VALUES (?,?,?, ?,?)",
        name,
        email,
        "hash",
        roleId,
        status);
  }

  private long userId(String email) {
    return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
  }

  @Test
  void structuredAccountNotificationsCaptureRoleTransitionAndOptionalLockReason() throws Exception {
    long groom = userId("groom@test.com");
    mvc.perform(
            patch("/api/club-manager/users/{id}/role", groom)
                .with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("roleName", "VETERINARIAN"))))
        .andExpect(status().isOk());
    var role =
        db.queryForMap("SELECT type,params,message FROM notifications WHERE user_id=?", groom);
    assertThat(role.get("type")).isEqualTo("ROLE_CHANGED");
    assertThat(role.get("message")).isEqualTo("");
    assertThat(json.readTree(role.get("params").toString()).get("old_role").asText())
        .isEqualTo("GROOM");
    assertThat(json.readTree(role.get("params").toString()).get("new_role").asText())
        .isEqualTo("VETERINARIAN");
    mvc.perform(
            patch("/api/club-manager/users/{id}/lock", groom)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk());
    String lock =
        db.queryForObject(
            "SELECT params FROM notifications WHERE user_id=? AND type='ACCOUNT_LOCKED'",
            String.class,
            groom);
    assertThat(json.readTree(lock).has("reason")).isFalse();
    mvc.perform(
            patch("/api/club-manager/users/{id}/unlock", groom)
                .with(user("manager@test.com").roles("CLUB_MANAGER")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/notifications").with(user("groom@test.com").roles("VETERINARIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unread_count").value(3));
    assertThat(
            db.queryForObject(
                "SELECT title FROM notifications WHERE user_id=? AND type='ACCOUNT_UNLOCKED'",
                String.class,
                groom))
        .isEqualTo("Tài khoản đã được mở khóa");
  }
}
