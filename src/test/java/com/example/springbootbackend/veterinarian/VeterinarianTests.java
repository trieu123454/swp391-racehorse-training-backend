package com.example.springbootbackend.veterinarian;

import com.example.springbootbackend.veterinarian.support.*;
import com.example.springbootbackend.veterinarian.care.CareScheduleService;
import com.example.springbootbackend.veterinarian.horse.VetHorseService;
import com.example.springbootbackend.auth.repository.AppUserRepository;
import com.example.springbootbackend.security.JwtService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:vet_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class VeterinarianTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired JwtService jwt;
    @Autowired VetHorseService horseService;
    @Autowired CareScheduleService careService;
    @Autowired PlatformTransactionManager transactions;
    @MockBean VetTime time;
    String horse, otherHorse;
    static final Instant NOW=Instant.parse("2026-09-28T04:00:00Z");
    static final LocalDate TODAY=LocalDate.of(2026,9,28);
    static final String VET="vet@test.com", OTHER="other@test.com";

    @BeforeEach void setup() {
        when(time.now()).thenReturn(NOW); when(time.utcNow()).thenReturn(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)); when(time.today()).thenReturn(TODAY);
        db.update("DELETE FROM horses"); db.update("DELETE FROM notifications"); db.update("DELETE FROM audit_logs");
        db.update("DELETE FROM refresh_tokens"); db.update("DELETE FROM users");
        for(String email:List.of(VET,OTHER)) addUser(email,"VETERINARIAN");
        addUser("trainer@test.com","HEAD_TRAINER"); addUser("groom@test.com","GROOM"); addUser("owner@test.com","HORSE_OWNER");
        addUser("manager@test.com","CLUB_MANAGER");
        horse=UUID.randomUUID().toString(); otherHorse=UUID.randomUUID().toString();
        db.update("INSERT INTO horses(id,horse_name) VALUES (?, 'Thunder'),(?, 'Cloud')",horse,otherHorse);
    }
    void addUser(String email,String role) { db.update("INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",email,email,role); }
    long uid(String email) { return db.queryForObject("SELECT user_id FROM users WHERE email=?",Long.class,email); }
    ResultActions call(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request.with(user(VET))); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object body) throws Exception { return request.contentType("application/json").content(json.writeValueAsString(body)); }
    JsonNode result(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    Map<String,Object> exam() { return new LinkedHashMap<>(Map.of("temperature_c",38.9,"heart_rate",42,"respiratory_rate",12)); }
    String createExam() throws Exception { return result(call(body(post("/api/horses/"+horse+"/health-exams"),exam())).andExpect(status().isCreated())).get("id").asText(); }
    String record(String horse) throws Exception { return result(call(body(post("/api/horses/"+horse+"/medical-records"),Map.of("diagnosis","Tendon injury"))).andExpect(status().isCreated())).get("id").asText(); }
    Map<String,Object> schedule(String horse,String date) { return new LinkedHashMap<>(Map.of("horse_id",horse,"care_type","Vaccination","next_due_date",date,"start_time","08:00","end_time","09:00","frequency_days",7)); }
    JsonNode createCare(Map<String,Object> body) throws Exception { return result(call(body(post("/api/periodic-care-schedules"),body)).andExpect(status().isCreated())); }

    @Test void unauthenticatedInvalidJwtAndWrongRoleAreRejected() throws Exception {
        String path="/api/horses/"+horse+"/health-exams";
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get(path).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        for(String email:List.of("groom@test.com","owner@test.com","trainer@test.com","manager@test.com"))
            mvc.perform(body(post(path),exam()).with(user(email))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    @Test void realJwtUsesCurrentRoleAndStatus() throws Exception {
        String token=jwt.createAccessToken(users.findByEmailIgnoreCase(VET).orElseThrow());
        String path="/api/horses/"+horse+"/health-exams";
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        db.update("UPDATE users SET status='LOCKED' WHERE email=?",VET);
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        db.update("UPDATE users SET status='APPROVED',must_change_password=TRUE WHERE email=?",VET);
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        db.update("UPDATE users SET must_change_password=FALSE,deleted_at=CURRENT_TIMESTAMP WHERE email=?",VET);
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void createExamIgnoresProtectedFieldsAndReturnsAlertsUtcDoctorAndAudit() throws Exception {
        var body=exam(); body.put("doctor_id",uid(OTHER)); body.put("horse_id",otherHorse); body.put("exam_date","2026-09-28T09:30:00+07:00");
        call(body(post("/api/horses/"+horse+"/health-exams"),body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctor.id").value(uid(VET))).andExpect(jsonPath("$.horse_id").value(horse))
                .andExpect(jsonPath("$.exam_date").value("2026-09-28T02:30:00Z"))
                .andExpect(jsonPath("$.alerts[0].field").value("temperature_c")).andExpect(jsonPath("$.suggest_medical_record").value(true));
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action_performed LIKE 'CREATE_HEALTH_EXAM:%' AND ip_address IS NOT NULL",Long.class,uid(VET))).isEqualTo(1);
    }
    @Test void validatesExamAndMissingOrDeletedHorses() throws Exception {
        var input=exam(); input.remove("temperature_c");
        call(body(post("/api/horses/"+horse+"/health-exams"),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("temperature_c"));
        input=exam(); input.put("temperature_c",60);
        call(body(post("/api/horses/"+horse+"/health-exams"),input)).andExpect(status().isBadRequest());
        input=exam(); input.put("heart_rate",42.5);
        call(body(post("/api/horses/"+horse+"/health-exams"),input)).andExpect(status().isBadRequest());
        input=exam(); input.put("exam_date","2026-09-28T04:05:01Z");
        call(body(post("/api/horses/"+horse+"/health-exams"),input)).andExpect(status().isBadRequest());
        call(body(post("/api/horses/"+UUID.randomUUID()+"/health-exams"),exam())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("HORSE_NOT_FOUND"));
        String id=createExam(); db.update("UPDATE horses SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",horse);
        call(get("/api/health-exams/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("HORSE_NOT_FOUND"));
        call(get("/api/health-exams/"+id+"/logs")).andExpect(status().isNotFound());
    }
    @Test void examPatchLogsOnlyTypedChangesAndOtherVetMayEdit() throws Exception {
        String id=createExam();
        call(body(patch("/api/health-exams/"+id),Map.of("temperature_c",38.90,"doctor_id",999))).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        assertThat(db.queryForObject("SELECT count(*) FROM health_exam_logs",Long.class)).isZero();
        mvc.perform(body(patch("/api/health-exams/"+id),Map.of("temperature_c",38.4)).with(user(OTHER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exam.doctor.id").value(uid(VET)));
        call(get("/api/health-exams/"+id+"/logs")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].edited_by.id").value(uid(OTHER)))
                .andExpect(jsonPath("$.data[0].changes.length()").value(1)).andExpect(jsonPath("$.data[0].changes[0].old_value").value(38.9));
        call(get("/api/horses/"+horse+"/health-exams")).andExpect(jsonPath("$.data[0].has_edits").value(true));
        call(get("/api/health-exams/"+id)).andExpect(jsonPath("$.last_edited_by.id").value(uid(OTHER)));
        call(delete("/api/health-exams/"+id)).andExpect(status().isMethodNotAllowed());
    }
    @Test void historyFailureRollsBackExamAndAudit() throws Exception {
        String id=createExam(); long audits=db.queryForObject("SELECT count(*) FROM audit_logs",Long.class);
        db.execute("ALTER TABLE health_exam_logs ADD CONSTRAINT test_reject_editor CHECK(edited_by<0)");
        try {
            assertThatThrownBy(()->call(body(patch("/api/health-exams/"+id),Map.of("temperature_c",38.4))))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally { db.execute("ALTER TABLE health_exam_logs DROP CONSTRAINT test_reject_editor"); }
        assertThat(db.queryForObject("SELECT temperature_c FROM health_exams WHERE id=?",Double.class,id)).isEqualTo(38.9);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs",Long.class)).isEqualTo(audits);
    }
    @Test void examDateFiltersUseVietnamDaysAndPaginationIsValidated() throws Exception {
        var body=exam(); body.put("exam_date","2026-09-27T18:00:00Z");
        call(body(post("/api/horses/"+horse+"/health-exams"),body)).andExpect(status().isCreated());
        call(get("/api/horses/"+horse+"/health-exams?from=2026-09-28&to=2026-09-28")).andExpect(jsonPath("$.total").value(1));
        call(get("/api/horses/"+horse+"/health-exams?limit=101")).andExpect(status().isBadRequest());
        call(get("/api/horses/"+horse+"/health-exams?page=0")).andExpect(status().isBadRequest());
        call(get("/api/horses/"+horse+"/health-exams?from=nope")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    @Test void diagnosesCannotLinkAnotherHorseExamAndCanBeEdited() throws Exception {
        String exam=createExam();
        call(body(post("/api/horses/"+otherHorse+"/medical-records"),Map.of("diagnosis","Injury","health_exam_id",exam))).andExpect(status().isBadRequest());
        String id=record(horse);
        call(body(patch("/api/medical-records/"+id),Map.of("diagnosis","Improving","horse_id",otherHorse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.horse_id").value(horse));
        call(get("/api/medical-records/"+id)).andExpect(jsonPath("$.prescriptions").isArray()).andExpect(jsonPath("$.injury_markers").isArray());
    }
    @Test void prescriptionsWarnOnDuplicateAndClosePermanently() throws Exception {
        String record=record(horse);
        var body=new LinkedHashMap<String,Object>(Map.of("drug_name","Drug","end_date","2026-10-05","doctor_id",uid(OTHER)));
        String id=result(call(body(post("/api/medical-records/"+record+"/prescriptions"),body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctor_id").value(uid(VET))).andExpect(jsonPath("$.status").value("Active"))).get("id").asText();
        body.put("drug_name","drug");
        call(body(post("/api/medical-records/"+record+"/prescriptions"),body)).andExpect(status().isCreated()).andExpect(jsonPath("$.warnings[0].code").value("DUPLICATE_DRUG"));
        call(body(patch("/api/prescriptions/"+id),Map.of("status","Completed"))).andExpect(status().isOk()).andExpect(jsonPath("$.end_date").value("2026-09-28"));
        call(body(patch("/api/prescriptions/"+id),Map.of("dosage","2g"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PRESCRIPTION_CLOSED"));
        call(body(post("/api/medical-records/"+record+"/prescriptions"),Map.of("drug_name","Drug","end_date","2026-09-27"))).andExpect(status().isBadRequest());
    }
    @Test void dietsEnforceCaseInsensitiveOverlapAndAllowOtherFeeds() throws Exception {
        var body=new LinkedHashMap<String,Object>(Map.of("feed_type"," Oats ","quantity_kg",3.5));
        String id=result(call(body(post("/api/horses/"+horse+"/diet-records"),body)).andExpect(status().isCreated())).get("id").asText();
        body.put("feed_type","oats");
        call(body(post("/api/horses/"+horse+"/diet-records"),body)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DIET_OVERLAP"));
        body.put("feed_type","Hay");
        call(body(post("/api/horses/"+horse+"/diet-records"),body)).andExpect(status().isCreated());
        call(body(patch("/api/diet-records/"+id),Map.of("feed_type","hay"))).andExpect(status().isConflict());
        call(body(patch("/api/diet-records/"+id),Map.of("quantity_kg",4))).andExpect(status().isOk());
        call(get("/api/horses/"+horse+"/diet-records")).andExpect(jsonPath("$.total").value(2));
    }
    @Test void injuriesAreAppendOnlyAndLatestGroupsByBodyPart() throws Exception {
        var input=new LinkedHashMap<String,Object>(Map.of("body_part","Left leg","severity","Severe","coordinate_x",0.4));
        call(body(post("/api/horses/"+horse+"/injury-markers"),input)).andExpect(status().isBadRequest());
        input.put("coordinate_y",0.7);
        call(body(post("/api/horses/"+horse+"/injury-markers"),input)).andExpect(status().isCreated()).andExpect(jsonPath("$.training_locked").value(true)).andExpect(jsonPath("$.suggest_lock").value(false)).andExpect(jsonPath("$.marked_by").value(uid(VET)));
        when(time.utcNow()).thenReturn(LocalDateTime.ofInstant(NOW.plusSeconds(1),ZoneOffset.UTC));
        input.put("recovery_status","Recovered"); input.put("severity","Mild");
        call(body(post("/api/horses/"+horse+"/injury-markers"),input)).andExpect(status().isCreated());
        call(get("/api/horses/"+horse+"/injury-markers?latest_only=true")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.data[0].recovery_status").value("Recovered"));
        call(get("/api/horses/"+horse+"/injury-markers")).andExpect(jsonPath("$.total").value(2));
    }
    @ParameterizedTest @ValueSource(strings={"Warning","Critical"})
    void locksNotifyOnceAndBlockTrainingUntilVetUnlocks(String level) throws Exception {
        Map<String,Object> lock=Map.of("lock_level",level,"lock_reason","Injury");
        call(body(put("/api/horses/"+horse+"/training-lock"),lock)).andExpect(status().isOk()).andExpect(jsonPath("$.was_locked").value(false));
        call(body(put("/api/horses/"+horse+"/training-lock"),lock)).andExpect(jsonPath("$.was_locked").value(true));
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Long.class,uid("trainer@test.com"))).isEqualTo(1);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{horseService.assertHorseNotLocked(horse); return null;}))
                .isInstanceOf(VetException.class).hasMessageContaining("khóa");
        createExam(); // Veterinary care remains available while training is locked.
        call(body(post("/api/horses/"+horse+"/training-unlock"),Map.of("reason","Recovered"))).andExpect(status().isOk());
        new TransactionTemplate(transactions).execute(status->{horseService.assertHorseNotLocked(horse); return null;});
        call(body(post("/api/horses/"+horse+"/training-unlock"),Map.of("reason","Recovered"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_LOCKED"));
        assertThat(db.queryForObject("SELECT lock_reason FROM horses WHERE id=?",String.class,horse)).isNull();
    }
    @Test void healthStatusDoesNotMutateWeightAndOverviewExcludesDeletedHorses() throws Exception {
        call(body(patch("/api/horses/"+horse+"/health-status"),Map.of("current_status","Injured","current_weight_kg",500,"note","x".repeat(400))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggest_lock").value(false))
                .andExpect(jsonPath("$.is_training_locked").value(true));
        assertThat(db.queryForObject("SELECT current_weight_kg FROM horses WHERE id=?",Double.class,horse)).isNull();
        assertThat(db.queryForObject("SELECT max(length(action_performed)) FROM audit_logs",Integer.class)).isLessThanOrEqualTo(255);
        db.update("UPDATE horses SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",otherHorse);
        call(get("/api/vet/health-overview")).andExpect(jsonPath("$.counts.Injured").value(1)).andExpect(jsonPath("$.horses.length()").value(1));
    }
    @Test void unbookedCareCreatesNoEventOrReminder() throws Exception {
        var input=schedule(horse,"2026-10-03"); input.put("book_event",false); input.remove("start_time"); input.remove("end_time");
        createCare(input); assertThat(db.queryForObject("SELECT count(*) FROM calendar_events",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Long.class)).isZero();
    }
    @Test void remindersAreUtcAndOnlyBecomeVisibleWhenDue() throws Exception {
        createCare(schedule(horse,"2026-10-03"));
        assertThat(db.queryForObject("SELECT scheduled_at FROM notifications",LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026,10,2,1,0));
        call(get("/api/notifications")).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.unread_count").value(0));
        var next=schedule(otherHorse,"2026-09-29"); createCare(next);
        call(get("/api/notifications")).andExpect(jsonPath("$.total").value(1));
    }
    void event(String horse,String type,String date,String start,String end) {
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,event_date,start_time,end_time,source_table,source_id) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),horse,type,LocalDate.parse(date),start==null?null:LocalTime.parse(start),end==null?null:LocalTime.parse(end),"test",UUID.randomUUID().toString());
    }
    @Test void conflictsCoverHorseDoctorAndAllDayButIgnoreCareTaskAndRest() throws Exception {
        event(horse,"Training","2026-10-03","08:30","09:30");
        call(body(post("/api/periodic-care-schedules"),schedule(horse,"2026-10-03"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.conflicts[0].event_type").value("Training"));
        event(horse,"Race","2026-10-04",null,null);
        call(body(post("/api/periodic-care-schedules"),schedule(horse,"2026-10-04"))).andExpect(status().isConflict());
        event(horse,"CareTask","2026-10-05",null,null); event(horse,"Rest","2026-10-05",null,null);
        createCare(schedule(horse,"2026-10-05"));
        call(body(post("/api/periodic-care-schedules"),schedule(otherHorse,"2026-10-05"))).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM periodic_care_schedules",Long.class)).isEqualTo(1);
    }
    @Test void cancelThenRebookSameDayReusesEventAndMovingReplacesFutureReminder() throws Exception {
        JsonNode care=createCare(schedule(horse,"2026-10-03")); String id=care.get("id").asText(),eventId=care.get("calendar_event_id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/cancel-event")).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Long.class)).isZero();
        call(body(post("/api/periodic-care-schedules/"+id+"/book"),Map.of("start_time","10:00","end_time","11:00"))).andExpect(status().isOk()).andExpect(jsonPath("$.calendar_event_id").value(eventId));
        call(body(patch("/api/periodic-care-schedules/"+id+"/event"),Map.of("event_date","2026-10-04"))).andExpect(status().isOk()).andExpect(jsonPath("$.next_due_date").value("2026-10-04"));
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT scheduled_at FROM notifications",LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026,10,3,1,0));
    }
    @Test void completingRecurrencePreservesHistoryAndRejectsImmediateRepeat() throws Exception {
        JsonNode care=createCare(schedule(horse,"2026-09-28")); String id=care.get("id").asText(),eventId=care.get("calendar_event_id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.next_due_date").value("2026-10-05")).andExpect(jsonPath("$.next_event.id").isString());
        assertThat(db.queryForObject("SELECT status FROM calendar_events WHERE id=?",String.class,eventId)).isEqualTo("Completed");
        call(post("/api/periodic-care-schedules/"+id+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_ALREADY_COMPLETED"));
        call(get("/api/vet/calendar?from=2026-09-28&to=2026-10-05")).andExpect(jsonPath("$.total").value(2));
    }
    @Test void completionSucceedsWhenNextEventConflictsAndOneOffDoesNotRecur() throws Exception {
        event(horse,"Training","2026-10-05","08:00","09:00");
        String id=createCare(schedule(horse,"2026-09-28")).get("id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.schedule.calendar_event_id").isEmpty())
                .andExpect(jsonPath("$.warnings[0].code").value("NEXT_EVENT_NOT_BOOKED"));
        var one=schedule(otherHorse,"2026-09-29"); one.remove("frequency_days");
        String oneId=createCare(one).get("id").asText();
        call(post("/api/periodic-care-schedules/"+oneId+"/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.next_event").isEmpty())
                .andExpect(jsonPath("$.schedule.event.status").value("Completed"));
    }
    @Test void notificationsAreOwnerScopedAndReadAllDoesNotReadFutureItems() throws Exception {
        createCare(schedule(horse,"2026-09-29")); createCare(schedule(horse,"2026-10-03"));
        String due=db.queryForObject("SELECT id FROM notifications WHERE scheduled_at<=?",String.class,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)).trim();
        mvc.perform(patch("/api/notifications/"+due+"/read").with(user(OTHER))).andExpect(status().isNotFound());
        call(post("/api/notifications/read-all")).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE is_read=FALSE",Long.class)).isEqualTo(1);
        call(get("/api/notifications?unread_only=true")).andExpect(jsonPath("$.total").value(0));
    }
    @Test void concurrentBookingsForSameDoctorCannotOverlap() throws Exception {
        var first=json.valueToTree(schedule(horse,"2026-10-03")); var second=json.valueToTree(schedule(otherHorse,"2026-10-03"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            List<Future<Integer>> futures=new ArrayList<>();
            for(JsonNode body:List.of(first,second)) futures.add(pool.submit(()-> {
                start.await();
                try { careService.create(VET,"127.0.0.1",body); return 201; }
                catch(VetException e) { return e.status(); }
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM periodic_care_schedules",Long.class)).isEqualTo(1);
    }

    @Test void movingOntoPreviouslyCancelledDateReusesOldEvent() throws Exception {
        var care=createCare(schedule(horse,"2026-10-03")); String id=care.get("id").asText(), original=care.get("calendar_event_id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/cancel-event")).andExpect(status().isOk());
        String second=result(call(body(post("/api/periodic-care-schedules/"+id+"/book"),Map.of("event_date","2026-10-04","start_time","08:00","end_time","09:00")))
                .andExpect(status().isOk())).get("calendar_event_id").asText();
        call(body(patch("/api/periodic-care-schedules/"+id+"/event"),Map.of("event_date","2026-10-03"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.calendar_event_id").value(original));
        assertThat(db.queryForObject("SELECT status FROM calendar_events WHERE id=?",String.class,second)).isEqualTo("Cancelled");
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Long.class)).isEqualTo(1);
    }

    @Test void careValidationBookingAndContextScope() throws Exception {
        var input=schedule(horse,"2026-10-03"); input.put("assigned_doctor_id",uid("trainer@test.com"));
        call(body(post("/api/periodic-care-schedules"),input)).andExpect(status().isBadRequest());
        input.put("assigned_doctor_id",uid(OTHER)); input.put("book_event",false);
        var care=createCare(input); String id=care.get("id").asText();
        call(body(post("/api/periodic-care-schedules/"+id+"/book"),Map.of("start_time","09:00","end_time","08:00"))).andExpect(status().isBadRequest());
        call(body(post("/api/periodic-care-schedules/"+id+"/book"),Map.of("start_time","08:00","end_time","09:00"))).andExpect(status().isOk());
        event(horse,"Training","2026-10-03","10:00","11:00");
        call(get("/api/vet/calendar?from=2026-10-03&to=2026-10-03")).andExpect(jsonPath("$.total").value(0));
        call(get("/api/vet/calendar?from=2026-10-03&to=2026-10-03&scope=all&include_context=true"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.context_events[0].context").value(true));
        db.update("UPDATE horses SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",horse);
        call(get("/api/vet/calendar?from=2026-10-03&to=2026-10-03&scope=all")).andExpect(jsonPath("$.total").value(0));
    }

    @Test void noBookedHoursStillCompletesAndOneOffUnbookedStaysCompleted() throws Exception {
        var input=schedule(horse,"2026-09-28"); input.put("book_event",false);
        String id=createCare(input).get("id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0].code").value("NEXT_EVENT_NOT_BOOKED"));
        input=schedule(otherHorse,"2026-09-28"); input.put("book_event",false); input.remove("frequency_days");
        id=createCare(input).get("id").asText();
        call(post("/api/periodic-care-schedules/"+id+"/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.event.status").value("Completed"));
        when(time.today()).thenReturn(TODAY.plusDays(1));
        call(get("/api/periodic-care-schedules?horse_id="+otherHorse)).andExpect(jsonPath("$.data[0].is_overdue").value(false));
    }

    @Test void lockBlocksUpcomingSessionsWithoutCancellingThem() throws Exception {
        String eventId=UUID.randomUUID().toString(), trainingId=UUID.randomUUID().toString();
        db.update("INSERT INTO calendar_events(id,horse_id,event_type,event_date,source_table,source_id) VALUES (?,?,'Training',?,'training_schedules',?)",eventId,horse,TODAY.plusDays(1),trainingId);
        db.update("INSERT INTO training_schedules(id,horse_id,calendar_event_id) VALUES (?,?,?)",trainingId,horse,eventId);
        call(body(put("/api/horses/"+horse+"/training-lock"),Map.of("lock_level","Critical","lock_reason","Injury")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upcoming_sessions[0].training_schedule_id").value(trainingId));
        assertThat(db.queryForObject("SELECT status FROM calendar_events WHERE id=?",String.class,eventId)).isEqualTo("Blocked");
        assertThat(db.queryForObject("SELECT status FROM training_schedules WHERE id=?",String.class,trainingId)).isEqualTo("Blocked");
    }

    @Test void notificationAndScheduleRollbackWhenAuditFails() throws Exception {
        db.execute("ALTER TABLE audit_logs ADD CONSTRAINT test_reject_audit CHECK(user_id<0)");
        try {
            assertThatThrownBy(()->call(body(post("/api/periodic-care-schedules"),schedule(horse,"2026-10-03"))))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally { db.execute("ALTER TABLE audit_logs DROP CONSTRAINT test_reject_audit"); }
        assertThat(db.queryForObject("SELECT count(*) FROM calendar_events",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM periodic_care_schedules",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Long.class)).isZero();
    }
}
