package com.example.springbootbackend.controller;

import com.example.springbootbackend.repository.RaceSimulationRepository;
import com.example.springbootbackend.repository.TrainingScheduleRepository;
import com.example.springbootbackend.service.RaceSimulationService;
import com.example.springbootbackend.service.TrainingSessionMaintenance;
import com.example.springbootbackend.service.VetTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
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
    @Autowired RaceSimulationService simulations;
    @Autowired RaceSimulationRepository simulationRepository;
    @Autowired TrainingScheduleRepository scheduleRepository;
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
        addUser("groom2@test.com", "GROOM");
        addUser("vet@test.com", "VETERINARIAN");
        horse = UUID.randomUUID().toString();
        otherHorse = UUID.randomUUID().toString();
        db.update("INSERT INTO horses(id,horse_name,readiness_status) VALUES (?, 'Thunder','Ready'),(?, 'Cloud','Ready')", horse, otherHorse);
    }

    @Test
    void sharedPlanUsesHorseIdsFromBody() throws Exception {
        Map<String, Object> payload = plan("Shared foundation");
        payload.put("horse_ids", List.of(horse, otherHorse));
        JsonNode created = result(call(body(post("/api/training-plans"), payload))
                .andExpect(status().isCreated()));
        assertThat(created.get("horse_ids").size()).isEqualTo(2);

        call(body(post("/api/training-plans"), plan("Missing horses")))
                .andExpect(status().isBadRequest());
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
    void metricsValidateRangesAlertAutomaticallyAndIncludeSimulationByDefault() throws Exception {
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        call(get("/api/horses/" + horse + "/training-metrics?include_simulated=false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
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
        call(body(post("/api/horses/" + horse + "/training-schedules"), schedule(db.queryForObject("SELECT id FROM training_plans WHERE horse_id=?", String.class, horse), "2026-10-02", "06:30", "07:30")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCHEDULE_CONFLICT"));
        db.update("INSERT INTO races(id,race_name,race_date) VALUES (?,?,?)", UUID.randomUUID().toString(), "Morning Cup", LocalDate.of(2026, 10, 2));
        String race = db.queryForObject("SELECT id FROM races", String.class);
        call(body(post("/api/horses/" + horse + "/race-entries"), Map.of("race_id", race)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCHEDULE_CONFLICT"));
    }

    @Test
    void simulationFollowsTargetsRejectsEarlyFinishAndIgnoresBrowserResults() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        setNow(Instant.parse("2026-10-02T23:00:00Z"), LocalDate.of(2026, 10, 3));
        JsonNode started = start(scheduleId, horse, "Normal");
        String id = started.get("simulation_id").asText();
        int duration = started.get("duration_seconds").asInt();
        assertThat(duration).isBetween(60, 3600).isLessThan(3600);
        assertThat(start(scheduleId, horse, "Normal").get("simulation_id").asText()).isEqualTo(id);
        call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FINISHED"));
        setNow(Instant.parse("2026-10-03T00:00:00Z"), LocalDate.of(2026, 10, 3));
        call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of("results", List.of(Map.of("avg_speed_kmh", 999)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Completed"));
        JsonNode detail = result(call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk()));
        assertThat(detail.get("actual_distance_meters").asDouble()).isCloseTo(1200, org.assertj.core.data.Offset.offset(0.5));
        assertThat(detail.get("results").get(0).get("avg_speed_kmh").asDouble()).isGreaterThan(1.2);
        assertThat(detail.get("readings").get(detail.get("readings").size() - 1).get("elapsed_seconds").asInt()).isEqualTo(duration);
        call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of())).andExpect(status().isOk());
        assertThat(count("race_simulation_metrics")).isEqualTo(1);
        assertThat(count("training_metrics_logs")).isEqualTo(1);
        assertThat(count("races")).isZero(); assertThat(count("horse_race_entries")).isZero();
        call(get("/api/horses/" + horse + "/training-metrics")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].is_simulated").value(true));
        call(get("/api/horses/" + horse + "/training-metrics?include_simulated=false")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void simulationRequiresCorrectHorseScheduleTimeAndAssignedGroom() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        call(body(post("/api/head-trainer/race-simulations"), Map.of("horse_ids", List.of(horse))))
                .andExpect(status().isBadRequest());
        call(body(post("/api/head-trainer/race-simulations"), request(scheduleId, horse, "Normal")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_OUTSIDE_TIME_WINDOW"));
        setNow(Instant.parse("2026-10-02T23:00:00Z"), LocalDate.of(2026, 10, 3));
        call(body(post("/api/head-trainer/race-simulations"), request(scheduleId, otherHorse, "Normal"))).andExpect(status().isBadRequest());
        call(body(post("/api/head-trainer/race-simulations"), Map.of("horse_ids", List.of(horse, otherHorse), "training_schedule_id", scheduleId))).andExpect(status().isBadRequest());
        db.update("UPDATE training_schedules SET assigned_groom_id=NULL WHERE id=?", scheduleId);
        call(body(post("/api/head-trainer/race-simulations"), request(scheduleId, horse, "Normal")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("GROOM_NOT_ASSIGNED"));
        assertThat(count("race_simulations")).isZero();
    }

    @Test
    void cancellationImmediatelySummarizesExactStopAndNeverCompletesSession() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        Instant began = Instant.parse("2026-10-02T23:00:00Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        JsonNode started = start(scheduleId, horse, "Normal");
        String id = started.get("simulation_id").asText();
        int interruptedAt = 60;
        setNow(began.plusSeconds(interruptedAt), LocalDate.of(2026, 10, 3));
        call(body(patch("/api/training-schedules/" + scheduleId), Map.of("status", "Cancelled"))).andExpect(status().isOk());
        assertThat(count("training_metrics_logs")).isEqualTo(1);
        JsonNode detail = result(call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk()));
        assertThat(detail.get("status").asText()).isEqualTo("Interrupted");
        assertThat(detail.get("actual_distance_meters").asDouble()).isGreaterThan(0).isLessThan(1200);
        assertThat(detail.get("stamina_score").isNull()).isTrue();
        assertThat(detail.get("recovery_heart_rate").isNull()).isTrue();
        JsonNode readings = detail.get("readings");
        assertThat(readings.get(readings.size() - 1).get("elapsed_seconds").asInt()).isEqualTo(interruptedAt);
        int readingsCount = readings.size();
        setNow(began.plusSeconds(4000), LocalDate.of(2026, 10, 3));
        call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Interrupted")).andExpect(jsonPath("$.ranking.length()").value(0));
        call(get("/api/head-trainer/race-simulations/" + id)).andExpect(jsonPath("$.readings.length()").value(readingsCount));
        assertThat(count("training_metrics_logs")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?", String.class, scheduleId)).isEqualTo("Cancelled");
        call(get("/api/horses/" + horse + "/training-analysis?from=2026-10-03&to=2026-10-03"))
                .andExpect(jsonPath("$.sessions[0].recommendation").value("REVIEW_INTERRUPTION"))
                .andExpect(jsonPath("$.sessions[0].max_heart_rate").isNumber());
    }

    @Test
    void liveAlertsAreDeduplicatedAndMedicalLockSavesPartialResults() throws Exception {
        String planId = createPlan(horse, "High load");
        call(body(patch("/api/training-plans/" + planId), Map.of("target_distance_meters", 50000, "target_intensity", "High", "target_track_surface", "Synthetic"))).andExpect(status().isOk());
        String scheduleId = createSchedule(horse, planId, "2026-10-03", "06:00", "07:00");
        Instant began = Instant.parse("2026-10-02T23:00:00Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        String id = start(scheduleId, horse, "Fatigue").get("simulation_id").asText();
        setNow(began.plusSeconds(2201), LocalDate.of(2026, 10, 3));
        call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE related_table='Race_Simulations' AND related_id=?", Long.class, id)).isEqualTo(1);
        call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk());
        mvc.perform(body(put("/api/horses/" + horse + "/training-lock"), Map.of("lock_level", "Critical", "lock_reason", "Stop training"))
                .with(user("vet@test.com").roles("VETERINARIAN"))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE related_table='Race_Simulations' AND related_id=?", Long.class, id)).isEqualTo(1);
        assertThat(count("training_metrics_logs")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?", String.class, scheduleId)).isEqualTo("Blocked");
        assertThat(db.queryForObject("SELECT status FROM race_simulations WHERE id=?", String.class, id)).isEqualTo("Interrupted");
        assertThat(db.queryForObject("SELECT has_injury_alert FROM training_metrics_logs", Boolean.class)).isTrue();
        assertThat(db.queryForObject("SELECT stamina_score FROM training_metrics_logs", Double.class)).isNull();
    }

    @Test
    void groupStartsSeparateLinkedSessionsAndRestoresAllRunningHorses() throws Exception {
        String first = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        String second = createSchedule(otherHorse, null, "2026-10-03", "06:00", "07:00");
        setNow(Instant.parse("2026-10-02T23:00:00Z"), LocalDate.of(2026, 10, 3));
        var input = Map.of("sessions", List.of(request(first, horse, "Normal"), request(second, otherHorse, "Normal")));
        call(body(post("/api/head-trainer/race-simulations/group"), input)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.length()").value(2));
        call(body(post("/api/head-trainer/race-simulations/group"), input)).andExpect(status().isCreated());
        assertThat(count("race_simulations")).isEqualTo(2);
        call(get("/api/head-trainer/race-simulations/active-sessions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        setNow(Instant.parse("2026-10-03T00:00:00Z"), LocalDate.of(2026, 10, 3));
        call(get("/api/head-trainer/race-simulations/active-sessions")).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(count("training_metrics_logs")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM training_metrics_logs m JOIN training_schedules ts ON ts.id=m.training_schedule_id WHERE m.horse_id=ts.horse_id", Long.class)).isEqualTo(2);
    }

    @Test
    void invalidGroupRollsBackEveryStartAndDuplicateHorseIsRejected() throws Exception {
        if (horse.compareTo(otherHorse) > 0) { String swap = horse; horse = otherHorse; otherHorse = swap; }
        String first = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        String second = createSchedule(otherHorse, null, "2026-10-03", "08:00", "09:00");
        setNow(Instant.parse("2026-10-02T23:00:00Z"), LocalDate.of(2026, 10, 3));
        call(body(post("/api/head-trainer/race-simulations/group"), Map.of("sessions", List.of(request(first, horse, "Normal"), request(second, otherHorse, "Normal")))))
                .andExpect(status().isConflict());
        assertThat(count("race_simulations")).isZero();
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?", String.class, first)).isEqualTo("Scheduled");
        call(body(post("/api/head-trainer/race-simulations/group"), Map.of("sessions", List.of(request(first, horse, "Normal"), request(first, horse, "Normal")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resumeReadingsAreStableAndIncludeNonCadenceFinalSample() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        Instant began = Instant.parse("2026-10-02T23:00:01Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        JsonNode started = start(scheduleId, horse, "Normal");
        String id = started.get("simulation_id").asText();
        int duration = started.get("duration_seconds").asInt();
        setNow(began.plusSeconds(duration - 1L), LocalDate.of(2026, 10, 3));
        JsonNode first = result(call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk())).get("readings");
        JsonNode repeat = result(call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk())).get("readings");
        assertThat(repeat).isEqualTo(first);
        call(get("/api/head-trainer/race-simulations/active")).andExpect(jsonPath("$.simulation_id").value(id));
        setNow(began.plusSeconds(duration), LocalDate.of(2026, 10, 3));
        JsonNode finalState = result(call(get("/api/head-trainer/race-simulations/" + id)).andExpect(status().isOk()));
        JsonNode readings = finalState.get("readings");
        assertThat(readings.get(readings.size() - 1).get("elapsed_seconds").asInt()).isEqualTo(duration);
        assertThat(finalState.get("actual_distance_meters").asDouble()).isCloseTo(1200, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void concurrentStartAndFinishPersistExactlyOneSessionAndSummary() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "06:01");
        Instant began = Instant.parse("2026-10-02T23:00:00Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> start(scheduleId, horse, "Normal").get("simulation_id").asText());
            var second = pool.submit(() -> start(scheduleId, horse, "Normal").get("simulation_id").asText());
            String id = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            setNow(began.plusSeconds(60), LocalDate.of(2026, 10, 3));
            var one = pool.submit(() -> call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of())).andExpect(status().isOk()));
            var two = pool.submit(() -> call(body(post("/api/head-trainer/race-simulations/" + id + "/finish"), Map.of())).andExpect(status().isOk()));
            one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("race_simulations")).isEqualTo(1);
        assertThat(count("race_simulation_metrics")).isEqualTo(1);
        assertThat(count("training_metrics_logs")).isEqualTo(1);
    }

    @Test
    void cancellationBackfillsAlertsWithoutBrowserPolling() throws Exception {
        String planId = createPlan(horse, "High load");
        call(body(patch("/api/training-plans/" + planId), Map.of("target_distance_meters", 50000, "target_intensity", "High", "target_track_surface", "Synthetic"))).andExpect(status().isOk());
        String scheduleId = createSchedule(horse, planId, "2026-10-03", "06:00", "07:00");
        Instant began = Instant.parse("2026-10-02T23:00:00Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        String id = start(scheduleId, horse, "Fatigue").get("simulation_id").asText();
        setNow(began.plusSeconds(2201), LocalDate.of(2026, 10, 3));
        call(body(patch("/api/training-schedules/" + scheduleId), Map.of("status", "Cancelled"))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE related_table='Race_Simulations' AND related_id=?", Long.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT max_heart_rate FROM training_metrics_logs", Integer.class)).isGreaterThan(220);
    }

    @Test
    void maintenanceFinishesWithoutAnActiveCreatorAndRepairsInterruptedHistory() throws Exception {
        String scheduleId = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        Instant began = Instant.parse("2026-10-02T23:00:00Z");
        setNow(began, LocalDate.of(2026, 10, 3));
        String id = start(scheduleId, horse, "Normal").get("simulation_id").asText();
        db.update("UPDATE users SET must_change_password=TRUE WHERE email=?", HEAD);
        setNow(began.plusSeconds(3600), LocalDate.of(2026, 10, 3));
        assertThat(simulations.advance(UUID.fromString(id)).get("status")).isEqualTo("Completed");
        assertThat(count("training_metrics_logs")).isEqualTo(1);
        db.update("UPDATE users SET must_change_password=FALSE WHERE email=?", HEAD);
        String next = createSchedule(horse, null, "2026-10-04", "06:00", "07:00");
        setNow(began.plusSeconds(86400), LocalDate.of(2026, 10, 4));
        String interrupted = start(next, horse, "Normal").get("simulation_id").asText();
        db.update("UPDATE race_simulations SET status='Interrupted',stop_reason='Historical cancellation',stopped_at=? WHERE id=?",
                LocalDateTime.ofInstant(began.plusSeconds(86417), ZoneOffset.UTC), interrupted);
        var repaired = simulations.advance(UUID.fromString(interrupted));
        assertThat(repaired.get("actual_distance_meters")).isNotNull();
        assertThat(repaired.get("stamina_score")).isNull();
        simulations.advance(UUID.fromString(interrupted));
        assertThat(count("training_metrics_logs")).isEqualTo(2);
    }

    @Test
    void expiredUnstartedSessionsBecomeMissedAndRestNeverGeneratesExercise() throws Exception {
        String missed = createSchedule(horse, null, "2026-10-03", "06:00", "07:00");
        var restInput = schedule(null, "2026-10-03", "06:00", "07:00");
        restInput.put("session_type", "Rest");
        String rest = result(call(body(post("/api/horses/" + otherHorse + "/training-schedules"), restInput)).andExpect(status().isCreated())).get("id").asText();
        setNow(Instant.parse("2026-10-02T23:00:00Z"), LocalDate.of(2026, 10, 3));
        call(body(post("/api/head-trainer/race-simulations"), request(rest, otherHorse, "Normal")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCHEDULE_NOT_STARTABLE"));
        db.update("UPDATE users SET must_change_password=TRUE WHERE email=?", HEAD);
        setNow(Instant.parse("2026-10-03T00:00:00Z"), LocalDate.of(2026, 10, 3));
        new TrainingSessionMaintenance(simulationRepository, scheduleRepository, simulations, time).tick();
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?", String.class, missed)).isEqualTo("Missed");
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?", String.class, rest)).isEqualTo("Scheduled");
        db.update("UPDATE users SET must_change_password=FALSE WHERE email=?", HEAD);
        call(body(patch("/api/training-schedules/" + rest), Map.of("status", "Completed"))).andExpect(status().isOk());
        assertThat(count("race_simulations")).isZero();
        assertThat(count("training_metrics_logs")).isZero();
    }

    private Map<String, Object> request(String scheduleId, String horseId, String scenario) {
        return Map.of("training_schedule_id", scheduleId, "horse_ids", List.of(horseId), "scenario", scenario);
    }
    private JsonNode start(String scheduleId, String horseId, String scenario) throws Exception {
        return result(call(body(post("/api/head-trainer/race-simulations"), request(scheduleId, horseId, scenario))).andExpect(status().isCreated()));
    }
    private long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private String createPlan(String horseId, String stage) throws Exception {
        return result(call(body(post("/api/horses/" + horseId + "/training-plans"), plan(stage)))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private String createSchedule(String horseId, String planId, String date, String start, String end) throws Exception {
        if (planId == null) {
            var existing = db.queryForList("SELECT id FROM training_plans WHERE horse_id=?", String.class, horseId);
            planId = existing.isEmpty() ? createPlan(horseId, "Foundation") : existing.getFirst();
        }
        var payload = schedule(planId, date, start, end);
        payload.put("assigned_groom_id", uid(horseId.equals(horse) ? "groom@test.com" : "groom2@test.com"));
        return result(call(body(post("/api/horses/" + horseId + "/training-schedules"), payload))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private Map<String, Object> plan(String stage) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage_name", stage); value.put("start_date", "2026-09-28"); value.put("end_date", "2026-10-31");
        value.put("objective", "Improve endurance"); value.put("target_distance_meters", 1200);
        value.put("target_workload_minutes", 180); value.put("target_track_surface", "Dirt");
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
