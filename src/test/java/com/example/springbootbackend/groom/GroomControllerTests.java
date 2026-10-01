package com.example.springbootbackend.groom;

import com.example.springbootbackend.auth.repository.AppUserRepository;
import com.example.springbootbackend.security.JwtService;
import com.example.springbootbackend.veterinarian.support.VetTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:groom_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroomControllerTests {
    private static final String GROOM = "groom@test.com";
    private static final String OTHER_GROOM = "other-groom@test.com";
    private static final String VET = "vet@test.com";
    private static final String MANAGER = "manager@test.com";
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired AppUserRepository users;
    @MockBean VetTime time;

    private long groomId;
    private long otherGroomId;
    private long vetId;
    private long managerId;
    private long ownerId;
    private String assignedHorse;
    private String otherHorse;
    private String stableId;

    @BeforeEach
    void setup() {
        when(time.now()).thenReturn(NOW);
        when(time.utcNow()).thenReturn(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(time.today()).thenReturn(TODAY);
        db.update("DELETE FROM supply_requests");
        db.update("DELETE FROM stable_incidents");
        db.update("DELETE FROM daily_task_logs");
        db.update("DELETE FROM training_schedules");
        db.update("DELETE FROM diet_records");
        db.update("DELETE FROM notifications");
        db.update("DELETE FROM audit_logs");
        db.update("DELETE FROM calendar_events");
        db.update("DELETE FROM inventory_items");
        db.update("DELETE FROM training_plans");
        db.update("DELETE FROM horses");
        db.update("DELETE FROM stable_boxes");
        db.update("DELETE FROM refresh_tokens");
        db.update("DELETE FROM users");
        groomId = addUser(GROOM, "GROOM");
        otherGroomId = addUser(OTHER_GROOM, "GROOM");
        vetId = addUser(VET, "VETERINARIAN");
        managerId = addUser(MANAGER, "CLUB_MANAGER");
        ownerId = addUser("owner@test.com", "HORSE_OWNER");
        assignedHorse = UUID.randomUUID().toString();
        otherHorse = UUID.randomUUID().toString();
        stableId = UUID.randomUUID().toString();
        db.update("INSERT INTO stable_boxes(id,box_code,section) VALUES (?,?,?)", stableId, "A-12", "A");
        db.update("INSERT INTO horses(id,horse_name,image_url,stable_box_id,owner_id,pedigree_father,lock_level,lock_reason,is_training_locked) "
                + "VALUES (?,?,?, ?,?,?,? ,?,TRUE)", assignedHorse, "Thunder Bolt", "https://img.test/horse.png",
                stableId, ownerId, "Secret Sire", "Warning", "Private medical note");
        db.update("INSERT INTO horses(id,horse_name) VALUES (?,?)", otherHorse, "Unassigned Horse");
    }

    @Test
    void horseReadsUseAssignmentScopeAndOnlyReturnTheGroomSummary() throws Exception {
        createTrainingAssignment(assignedHorse, groomId, TODAY, "06:00", "07:00", "Foundation support");
        mvc.perform(get("/api/groom/my-horses").with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].horse_name").value("Thunder Bolt"))
                .andExpect(jsonPath("$.data[0].stable_box.box_code").value("A-12"))
                .andExpect(jsonPath("$.data[0].owner_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].pedigree_father").doesNotExist());

        mvc.perform(get("/api/groom/horses/{id}", assignedHorse).with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.is_training_locked").value(true))
                .andExpect(jsonPath("$.lock_level").value("Warning"))
                .andExpect(jsonPath("$.lock_reason").doesNotExist())
                .andExpect(jsonPath("$.owner_id").doesNotExist())
                .andExpect(jsonPath("$.pedigree_father").doesNotExist());
        mvc.perform(get("/api/groom/horses/{id}", otherHorse).with(groom()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/horses/{id}", otherHorse).with(groom()))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordChangeIsRequiredForGroomRoutes() throws Exception {
        db.update("UPDATE users SET must_change_password=TRUE WHERE user_id=?", groomId);
        String token = jwt.createAccessToken(users.findByEmailIgnoreCase(GROOM).orElseThrow());
        mvc.perform(get("/api/groom/my-horses").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void calendarMergesTrainingAndCareTasksAndKeepsCareTasksAfterTraining() throws Exception {
        createTrainingAssignment(assignedHorse, groomId, TODAY, "06:00", "07:00", "Help with warm-up");
        createTask(assignedHorse, groomId, "IceBath", TODAY);
        createTask(assignedHorse, groomId, "Feeding", TODAY);
        mvc.perform(get("/api/groom/calendar?date=" + TODAY).with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].type").value("training"))
                .andExpect(jsonPath("$.events[0].start_time").value("06:00"))
                .andExpect(jsonPath("$.events[1].task_type").value("Feeding"))
                .andExpect(jsonPath("$.events[2].task_type").value("IceBath"));
    }

    @Test
    void taskCompletionRejectsAnotherGroomAndIsIdempotentForItsOwner() throws Exception {
        String ownTask = createTask(assignedHorse, groomId, "Feeding", TODAY);
        String otherTask = createTask(otherHorse, otherGroomId, "Cleaning", TODAY);
        mvc.perform(patch("/api/groom/daily-tasks/{id}/complete", otherTask).with(groom()))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/groom/daily-tasks/{id}/complete", ownTask).with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Completed"))
                .andExpect(jsonPath("$.already_completed").value(false));
        mvc.perform(patch("/api/groom/daily-tasks/{id}/complete", ownTask).with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.already_completed").value(true));
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed LIKE 'COMPLETE_GROOM_TASK:%'", Long.class)).isEqualTo(1);
    }

    @Test
    void incidentRequiresAssignmentAndNotifiesBothVeterinariansAndManagers() throws Exception {
        createTask(assignedHorse, groomId, "Feeding", TODAY);
        String body = json.writeValueAsString(Map.of("issue_description", "Horse refused food this morning",
                "image_url", "https://storage.test/incident.jpg"));
        mvc.perform(post("/api/groom/horses/{id}/incidents", otherHorse).with(groom())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/groom/horses/{id}/incidents", assignedHorse).with(groom())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("Pending"))
                .andExpect(jsonPath("$.image_url").value("https://storage.test/incident.jpg"));
        assertThat(notificationCount(vetId, "Stable_Incidents")).isEqualTo(1);
        assertThat(notificationCount(managerId, "Stable_Incidents")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed LIKE 'REPORT_STABLE_INCIDENT:%'", Long.class)).isEqualTo(1);
    }

    @Test
    void inventoryFlagsLowStockAndSupplyRequestsWarnOnPendingDuplicates() throws Exception {
        String item = UUID.randomUUID().toString();
        db.update("INSERT INTO inventory_items(id,item_name,category,unit,quantity_in_stock,reorder_threshold) "
                + "VALUES (?,?,?,?,?,?)", item, "Oats", "Feed", "kg", new java.math.BigDecimal("2.00"), new java.math.BigDecimal("5.00"));
        db.update("INSERT INTO supply_requests(id,requested_by,item_id,quantity_requested,status) VALUES (?,?,?,?,'Pending')",
                UUID.randomUUID().toString(), groomId, item, new java.math.BigDecimal("10.00"));
        mvc.perform(get("/api/groom/inventory?low_stock_only=true").with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].is_low_stock").value(true));
        String body = json.writeValueAsString(Map.of("item_id", item, "quantity_requested", 20,
                "reason", "Only two days of feed remain"));
        mvc.perform(post("/api/groom/supply-requests").with(groom()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("Pending"))
                .andExpect(jsonPath("$.warnings[0].code").value("DUPLICATE_PENDING_REQUEST"));
        assertThat(notificationCount(managerId, "Supply_Requests")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed LIKE 'CREATE_SUPPLY_REQUEST:%'", Long.class)).isEqualTo(1);
    }

    @Test
    void dietReadReturnsOnlyActiveRecordsAndHasNoWriteRoute() throws Exception {
        createTrainingAssignment(assignedHorse, groomId, TODAY, "06:00", "07:00", "Support");
        db.update("INSERT INTO diet_records(id,horse_id,feed_type,quantity_kg,feeding_frequency,effective_date,end_date) "
                        + "VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), assignedHorse, "Oats", new java.math.BigDecimal("3.50"), "Three times daily",
                TODAY.minusDays(10), TODAY.plusDays(2));
        db.update("INSERT INTO diet_records(id,horse_id,feed_type,effective_date,end_date) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), assignedHorse, "Old feed", TODAY.minusDays(30), TODAY.minusDays(1));
        mvc.perform(get("/api/groom/horses/{id}/diet-records", assignedHorse).with(groom()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].feed_type").value("Oats"));
        mvc.perform(patch("/api/groom/horses/{id}/diet-records", assignedHorse).with(groom())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private long addUser(String email, String role) {
        db.update("INSERT INTO users(full_name,email,password_hash,role_id,status) "
                        + "SELECT ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",
                email, email, role);
        return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
    }

    private String createTrainingAssignment(String horse, long assignedGroom, LocalDate date,
            String start, String end, String notes) {
        String schedule = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,start_time,end_time,status,source_table,source_id) "
                        + "VALUES (?,?, 'Training','Training',?,?,?,'Scheduled','training_schedules',?)",
                event, horse, date, start, end, schedule);
        db.update("INSERT INTO training_schedules(id,calendar_event_id,horse_id,assigned_groom_id,session_type,status,notes) "
                        + "VALUES (?,?,?,?,'Training','Scheduled',?)",
                schedule, event, horse, assignedGroom, notes);
        return schedule;
    }

    private String createTask(String horse, long assignedGroom, String type, LocalDate date) {
        String task = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,title,event_date,status,source_table,source_id) "
                        + "VALUES (?,?, 'CareTask',?,?, 'Scheduled','daily_task_logs',?)",
                event, horse, type, date, task);
        db.update("INSERT INTO daily_task_logs(id,calendar_event_id,horse_id,groom_id,task_type,status) "
                        + "VALUES (?,?,?,?,?,'Pending')",
                task, event, horse, assignedGroom, type);
        return task;
    }

    private long notificationCount(long recipient, String table) {
        return db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND related_table=?",
                Long.class, recipient, table);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor groom() {
        return user(GROOM).roles("GROOM");
    }
}
