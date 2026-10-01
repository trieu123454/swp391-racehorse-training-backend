package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.VetTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:head_trainer_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HeadTrainerControllerTests {
    private static final String HEAD = "head@test.com";
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean VetTime time;
    String horse;
    String otherHorse;

    @BeforeEach
    void setup() {
        when(time.now()).thenReturn(NOW);
        when(time.utcNow()).thenReturn(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(time.today()).thenReturn(TODAY);
        db.update("DELETE FROM race_simulation_metrics");
        db.update("DELETE FROM race_simulation_horses");
        db.update("DELETE FROM race_simulations");
        db.update("DELETE FROM horse_race_entries");
        db.update("DELETE FROM training_metrics_logs");
        db.update("DELETE FROM training_schedules");
        db.update("DELETE FROM calendar_events");
        db.update("DELETE FROM training_plan_logs");
        db.update("DELETE FROM training_plans");
        db.update("DELETE FROM races");
        db.update("DELETE FROM notifications");
        db.update("DELETE FROM audit_logs");
        db.update("DELETE FROM horses");
        db.update("DELETE FROM refresh_tokens");
        db.update("DELETE FROM users");
        addUser(HEAD, "HEAD_TRAINER");
        addUser("groom@test.com", "GROOM");
        addUser("vet@test.com", "VETERINARIAN");
        horse = UUID.randomUUID().toString();
        otherHorse = UUID.randomUUID().toString();
        db.update("INSERT INTO horses(id,horse_name,readiness_status) VALUES (?, 'Thunder','Ready'),(?, 'Cloud','Ready')", horse, otherHorse);
    }

    @Test
    void trainerRoleIsRequiredAndLockBlocksPlanScheduleAndRaceEntry() throws Exception {
        mvc.perform(get("/api/head-trainer/overview")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/head-trainer/overview").with(user("vet@test.com").roles("VETERINARIAN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        db.update("UPDATE horses SET is_training_locked=TRUE,lock_level='Critical',lock_reason='Vet requested rest' WHERE id=?", horse);
        call(body(post("/api/horses/" + horse + "/training-plans"), plan("Recovery")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRAINING_LOCKED"))
                .andExpect(jsonPath("$.error.details.lock_level").value("Critical"));
        call(body(post("/api/horses/" + horse + "/training-schedules"), schedule(null, "2026-10-01", "08:00", "09:00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRAINING_LOCKED"));
        db.update("INSERT INTO races(id,race_name,race_date) VALUES (?,?,?)", UUID.randomUUID().toString(), "Autumn Cup", LocalDate.of(2026, 10, 10));
        String race = db.queryForObject("SELECT id FROM races", String.class);
        call(body(post("/api/horses/" + horse + "/race-entries"), Map.of("race_id", race)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRAINING_LOCKED"));
    }

    @Test
    void metricsValidateRangesAlertAutomaticallyAndHideSimulationByDefault() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-09-28", "13:00", "14:00");
        db.update("UPDATE training_schedules SET status='Completed' WHERE id=?", scheduleId);
        db.update("UPDATE calendar_events SET status='Completed' WHERE source_table='training_schedules' AND source_id=?", scheduleId);
        setNow(Instant.parse("2026-09-28T08:00:00Z"), TODAY);
        call(body(post("/api/training-schedules/" + scheduleId + "/metrics"), metric(300)))
                .andExpect(status().isBadRequest());
        call(body(post("/api/training-schedules/" + scheduleId + "/metrics"), metric(225)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.has_injury_alert").value(true))
                .andExpect(jsonPath("$.suggest_notify_vet").value(true))
                .andExpect(jsonPath("$.training_schedule_id").value(scheduleId));
        db.update("INSERT INTO training_metrics_logs(id,horse_id,recorded_by,max_heart_rate,avg_speed_kmh,stamina_score,is_simulated)"
                + " VALUES (?,?,?,?,?,?,TRUE)", UUID.randomUUID().toString(), horse, uid(HEAD), 170, 48.2, 7.5);
        call(get("/api/horses/" + horse + "/training-metrics"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].has_injury_alert").value(true))
                .andExpect(jsonPath("$.length()").value(1));
        call(get("/api/horses/" + horse + "/training-metrics?include_simulated=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed LIKE 'RECORD_TRAINING_METRICS:%'", Long.class)).isEqualTo(1);
    }

    @Test
    void planDeadlineRejectsTwentyHoursAndUnscheduledPlanCanBeEditedWithFieldLog() throws Exception {
        String scheduledPlan = createPlan(horse, "Foundation");
        createSchedule(horse, scheduledPlan, "2026-09-29", "07:00", "08:00");
        call(body(patch("/api/training-plans/" + scheduledPlan), Map.of("objective", "Updated objective")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PLAN_UPDATE_TOO_LATE"))
                .andExpect(jsonPath("$.error.details.hours_remaining").value(20));

        String unscheduledPlan = createPlan(otherHorse, "Speed block");
        call(body(patch("/api/training-plans/" + unscheduledPlan), Map.of("stage_name", "Speed block revised")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage_name").value("Speed block revised"));
        assertThat(db.queryForObject("SELECT count(*) FROM training_plan_logs WHERE training_plan_id=?", Long.class, unscheduledPlan)).isEqualTo(1);
        String changed = db.queryForObject("SELECT new_data_json FROM training_plan_logs WHERE training_plan_id=?", String.class, unscheduledPlan);
        assertThat(changed).contains("stage_name").doesNotContain("objective");
    }

    @Test
    void overlappingSessionsAndSameDayRacesAreRejected() throws Exception {
        createSchedule(horse, null, "2026-10-02", "06:00", "07:00");
        call(body(post("/api/horses/" + horse + "/training-schedules"), schedule(null, "2026-10-02", "06:30", "07:30")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCHEDULE_CONFLICT"));
        db.update("INSERT INTO races(id,race_name,race_date) VALUES (?,?,?)", UUID.randomUUID().toString(), "Morning Cup", LocalDate.of(2026, 10, 2));
        String race = db.queryForObject("SELECT id FROM races", String.class);
        call(body(post("/api/horses/" + horse + "/race-entries"), Map.of("race_id", race)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCHEDULE_CONFLICT"));
    }

    @Test
    void simulationChecksInitialParticipantsRanksAndCopiesMarkedRows() throws Exception {
        String linkedSchedule = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        setNow(Instant.parse("2026-10-02T23:30:00Z"), LocalDate.of(2026, 10, 3));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("horse_ids", List.of(horse, otherHorse));
        request.put("distance_meters", 1200);
        request.put("duration_seconds", 180);
        request.put("training_schedule_id", linkedSchedule);
        JsonNode started = result(call(body(post("/api/head-trainer/race-simulations"), request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.horses.length()").value(2)));
        String simulationId = started.get("simulation_id").asText();
        call(body(post("/api/head-trainer/race-simulations/" + simulationId + "/finish"),
                Map.of("results", List.of(result(horse, 82.4), result(otherHorse, 85.1)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ranking[0].rank").value(1));
        assertThat(db.queryForObject("SELECT count(*) FROM race_simulation_metrics WHERE simulation_id=?", Long.class, simulationId)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM training_metrics_logs WHERE is_simulated=TRUE", Long.class)).isZero();
    }

    @Test
    void simulationValidatesFieldSizeResultCompletenessAndCopiesAsSimulated() throws Exception {
        String tooSmall = "{\"horse_ids\":[\"" + horse + "\"],\"distance_meters\":1200,\"duration_seconds\":100}";
        mvc.perform(post("/api/head-trainer/race-simulations").with(trainer()).contentType("application/json").content(tooSmall))
                .andExpect(status().isBadRequest());
        String tooMany = json.writeValueAsString(Map.of("horse_ids", List.of(horse, horse, horse, horse, horse, horse),
                "distance_meters", 1200, "duration_seconds", 100));
        mvc.perform(post("/api/head-trainer/race-simulations").with(trainer()).contentType("application/json").content(tooMany))
                .andExpect(status().isBadRequest());
        String scheduleId = createSchedule(horse, null, "2026-10-04", "06:00", "07:00");
        setNow(Instant.parse("2026-10-03T23:30:00Z"), LocalDate.of(2026, 10, 4));
        JsonNode started = result(call(body(post("/api/head-trainer/race-simulations"), Map.of(
                "horse_ids", List.of(horse, otherHorse), "distance_meters", 1200, "duration_seconds", 100,
                "training_schedule_id", scheduleId))).andExpect(status().isCreated()));
        String simulationId = started.get("simulation_id").asText();
        call(body(post("/api/head-trainer/race-simulations/" + simulationId + "/finish"),
                Map.of("results", List.of(result(horse, 82.4)))))
                .andExpect(status().isBadRequest());
        call(body(post("/api/head-trainer/race-simulations/" + simulationId + "/finish"), Map.of(
                "results", List.of(result(horse, 82.4), result(otherHorse, 85.1)), "copy_to_metrics", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Completed"));
        assertThat(db.queryForObject("SELECT count(*) FROM training_metrics_logs WHERE is_simulated=TRUE", Long.class)).isEqualTo(2);
        call(get("/api/horses/" + horse + "/training-metrics")).andExpect(jsonPath("$.length()").value(0));
        call(get("/api/horses/" + horse + "/training-metrics?include_simulated=true"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].is_simulated").value(true));
    }

    private String createPlan(String horseId, String stage) throws Exception {
        return result(call(body(post("/api/horses/" + horseId + "/training-plans"), plan(stage)))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private String createSchedule(String horseId, String planId, String date, String start, String end) throws Exception {
        return result(call(body(post("/api/horses/" + horseId + "/training-schedules"), schedule(planId, date, start, end)))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private Map<String, Object> plan(String stage) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage_name", stage); value.put("start_date", "2026-09-28"); value.put("end_date", "2026-10-31");
        value.put("objective", "Improve endurance"); value.put("target_distance_meters", 1200);
        value.put("target_intensity", "Medium"); value.put("update_deadline_hours", 24);
        return value;
    }

    private Map<String, Object> schedule(String planId, String date, String start, String end) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("training_plan_id", planId); value.put("session_type", "Training");
        value.put("event_date", date); value.put("start_time", start); value.put("end_time", end);
        return value;
    }

    private Map<String, Object> metric(int heartRate) {
        return Map.of("body_weight_kg", 452.0, "max_heart_rate", heartRate, "avg_speed_kmh", 45.2,
                "stamina_score", 7.5, "trainer_review", "Strong finish");
    }

    private Map<String, Object> result(String horseId, double finishTime) {
        return Map.of("horse_id", horseId, "finish_time_seconds", finishTime, "avg_speed_kmh", 52.4,
                "max_heart_rate", 188, "max_bp_systolic", 168, "max_bp_diastolic", 92);
    }

    private void addUser(String email, String role) {
        db.update("INSERT INTO users(full_name,email,password_hash,role_id,status)"
                + " SELECT ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?", email, email, role);
    }

    private long uid(String email) { return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email); }
    private void setNow(Instant instant, LocalDate date) {
        when(time.now()).thenReturn(instant);
        when(time.utcNow()).thenReturn(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        when(time.today()).thenReturn(date);
    }
    private RequestPostProcessor trainer() { return user(HEAD).roles("HEAD_TRAINER"); }
    private org.springframework.test.web.servlet.ResultActions call(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request.with(trainer())); }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsString(value));
    }
    private JsonNode result(org.springframework.test.web.servlet.ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
}
