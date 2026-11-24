package com.example.springbootbackend.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.springbootbackend.service.RegistrationStorage;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.config.import=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.url=jdbc:h2:mem:manager_registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class HorseRegistrationReviewTests {
  static final String API = "/api/club-manager/horse-registration-requests";
  @Autowired MockMvc mvc;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @MockBean RegistrationStorage storage;
  long owner, manager, other;
  String request, box;

  @BeforeEach
  void setup() {
    owner = addUser("owner@review.test", "HORSE_OWNER");
    manager = addUser("manager@review.test", "CLUB_MANAGER");
    other = addUser("other@review.test", "HORSE_OWNER");
    request = UUID.randomUUID().toString();
    box = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO stable_boxes(id,box_code,capacity,status) VALUES (?,'REVIEW',1,'Available')",
        box);
    db.update(
        "INSERT INTO"
            + " horse_registration_requests(id,owner_id,request_type,horse_name,owner_note,microchip_number,passport_number)"
            + " VALUES (?,?,'REGISTER','Thunder','Owner note','chip-123','pass-123')",
        request,
        owner);
    when(storage.signedUrl(anyString())).thenReturn("https://private.test/signed?expires=300");
  }

  long addUser(String email, String role) {
    db.update(
        "INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT 'Test"
            + " User',?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",
        email,
        role);
    return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
  }

  ResultActions review(String id, Map<String, ?> body) throws Exception {
    entityManager.clear();
    return mvc.perform(
        patch(API + "/" + id)
            .with(user("manager@review.test").roles("CLUB_MANAGER"))
            .contentType("application/json")
            .content(json.writeValueAsBytes(body)));
  }

  Map<String, Object> approval() {
    return Map.of("action", "APPROVE", "stableBoxId", box, "reviewNote", "Verified");
  }

  @Test
  void listContainsOwnerSignedFilesAndArraysAndExcludesFinalDecisions() throws Exception {
    String path = "registrations/" + request + "/" + UUID.randomUUID() + ".png";
    db.update(
        "UPDATE horse_registration_requests SET horse_image_path=? WHERE id=?", path, request);
    db.update(
        "INSERT INTO"
            + " horse_registration_documents(id,request_id,object_path,original_filename,content_type,file_size,document_type)"
            + " VALUES (?,?,?,'proof.png','image/png',12,'OWNERSHIP_PROOF')",
        UUID.randomUUID().toString(),
        request,
        path);
    mvc.perform(get(API).with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].owner.id").value(owner))
        .andExpect(jsonPath("$[0].owner_email").value("owner@review.test"))
        .andExpect(jsonPath("$[0].horseImageUrl").value("https://private.test/signed?expires=300"))
        .andExpect(jsonPath("$[0].documents[0].url").exists())
        .andExpect(jsonPath("$[0].history").isArray());
    review(request, Map.of("action", "REJECT", "reviewNote", "Invalid proof"))
        .andExpect(status().isOk());
    mvc.perform(get(API).with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void approvalCreatesHorseWithOwnershipAndHistoryAndCannotRepeat() throws Exception {
    var result =
        review(request, approval())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.reviewed_by").value(manager))
            .andExpect(jsonPath("$.review_note").value("Verified"))
            .andExpect(jsonPath("$.history[0].action").value("REVIEW_APPROVED"))
            .andExpect(jsonPath("$.history[0].from_status").value("PENDING"))
            .andExpect(jsonPath("$.history[0].to_status").value("APPROVED"))
            .andReturn();
    String horse =
        json.readTree(result.getResponse().getContentAsString()).path("approved_horse_id").asText();
    var row = db.queryForMap("SELECT * FROM horses WHERE id=?", horse);
    assertThat(row.get("owner_id")).isEqualTo(owner);
    assertThat(row.get("stable_box_id")).isEqualTo(box);
    assertThat(row.get("microchip_number")).isEqualTo("chip-123");
    assertThat(row.get("passport_number")).isEqualTo("pass-123");
    assertThat(
            json.readTree(
                    db.queryForObject(
                        "SELECT ownership_history_json FROM horses WHERE id=?",
                        String.class,
                        horse))
                .size())
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=?", Long.class, owner))
        .isEqualTo(1);
    review(request, approval()).andExpect(status().isConflict());
    assertThat(
            json.readTree(
                    db.queryForObject(
                        "SELECT history_json FROM horse_registration_requests WHERE id=?",
                        String.class,
                        request))
                .size())
        .isEqualTo(1);
  }

  @Test
  void informationRequestAllowsOwnerSupplementThenApprovalAndPreservesHistory() throws Exception {
    review(request, Map.of("action", "REQUEST_INFORMATION", "reviewNote", "Upload passport"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_INFORMATION"))
        .andExpect(jsonPath("$.history[0].review_note").value("Upload passport"));
    mvc.perform(
            get(API)
                .param("status", "NEEDS_INFORMATION")
                .with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(
            patch("/api/horse-owner/horse-registration-requests/" + request)
                .with(user("owner@review.test").roles("HORSE_OWNER"))
                .contentType("application/json")
                .content("{\"ownerNote\":\"Supplemented\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.history.length()").value(2));
    review(request, approval())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.history.length()").value(3));
  }

  @Test
  void rejectionDoesNotCreateAHorseAndIsFinal() throws Exception {
    review(request, Map.of("action", "REJECT", "review_note", "Invalid documents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    assertThat(db.queryForObject("SELECT count(*) FROM horses", Long.class)).isZero();
    review(request, approval()).andExpect(status().isConflict());
  }

  @Test
  void approvalRequiresAvailableStableAndActiveOwnerAndRollsBack() throws Exception {
    review(request, Map.of("action", "APPROVE")).andExpect(status().isBadRequest());
    review(request, Map.of("action", "APPROVE", "stableBoxId", UUID.randomUUID()))
        .andExpect(status().isBadRequest());
    db.update("UPDATE stable_boxes SET status='Maintenance' WHERE id=?", box);
    review(request, approval()).andExpect(status().isConflict());
    db.update("UPDATE stable_boxes SET status='Available' WHERE id=?", box);
    db.update(
        "INSERT INTO horses(id,horse_name,stable_box_id) VALUES (?,'Occupant',?)",
        UUID.randomUUID().toString(),
        box);
    review(request, approval()).andExpect(status().isConflict());
    assertThat(
            db.queryForObject(
                "SELECT status FROM horse_registration_requests WHERE id=?", String.class, request))
        .isEqualTo("PENDING");
    assertThat(db.queryForObject("SELECT count(*) FROM horse_registration_history", Long.class))
        .isZero();
    db.update("DELETE FROM horses");
    db.update("UPDATE users SET status='LOCKED' WHERE user_id=?", owner);
    review(request, approval()).andExpect(status().isConflict());
  }

  @Test
  void linkAssignsUnownedHorseButCannotTakeAnotherOwnersHorse() throws Exception {
    String horse = UUID.randomUUID().toString();
    db.update("UPDATE horse_registration_requests SET request_type='LINK' WHERE id=?", request);
    db.update(
        "INSERT INTO horses(id,horse_name,stable_box_id,owner_id) VALUES (?,'Existing Horse',?,?)",
        horse,
        box,
        other);
    review(request, Map.of("action", "APPROVE", "horseId", horse))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AWAITING_OWNER_CONSENT"));
    db.update("UPDATE horse_registration_requests SET status='PENDING' WHERE id=?", request);
    review(request, Map.of("action", "APPROVE")).andExpect(status().isBadRequest());
    review(request, Map.of("action", "APPROVE", "horseId", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    db.update("UPDATE horses SET owner_id=NULL WHERE id=?", horse);
    review(request, Map.of("action", "APPROVE", "horse_id", horse))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved_horse_id").value(horse));
    assertThat(db.queryForObject("SELECT count(*) FROM horses", Long.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT owner_id FROM horses WHERE id=?", Long.class, horse))
        .isEqualTo(owner);
    assertThat(db.queryForObject("SELECT horse_name FROM horses WHERE id=?", String.class, horse))
        .isEqualTo("Existing Horse");
  }

  @Test
  void invalidActionsNotesAndIdsReturn400Or404() throws Exception {
    for (var body :
        List.of(
            Map.of("action", "BAD"),
            Map.of("action", "REJECT"),
            Map.of("action", "REQUEST_INFORMATION", "reviewNote", " "),
            Map.of("reviewNote", "missing action")))
      review(request, body).andExpect(status().isBadRequest());
    review(request, Map.of("action", "APPROVE", "stableBoxId", box, "horseId", UUID.randomUUID()))
        .andExpect(status().isBadRequest());
    review(UUID.randomUUID().toString(), approval()).andExpect(status().isNotFound());
    review("bad-id", approval()).andExpect(status().isBadRequest());
    mvc.perform(
            get(API)
                .param("status", "INVALID")
                .with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void wrongRolesAndRestrictedManagersCannotAccessEitherEndpoint() throws Exception {
    for (String role : List.of("HORSE_OWNER", "HEAD_TRAINER", "GROOM", "VETERINARIAN")) {
      mvc.perform(get(API).with(user("owner@review.test").roles(role)))
          .andExpect(status().isForbidden());
      mvc.perform(
              patch(API + "/" + request)
                  .with(user("owner@review.test").roles(role))
                  .contentType("application/json")
                  .content(json.writeValueAsBytes(approval())))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get(API)).andExpect(status().isUnauthorized());
    db.update("UPDATE users SET must_change_password=TRUE WHERE user_id=?", manager);
    mvc.perform(get(API).with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isForbidden());
    review(request, approval()).andExpect(status().isForbidden());
    db.update(
        "UPDATE users SET must_change_password=FALSE,status='LOCKED' WHERE user_id=?", manager);
    mvc.perform(get(API).with(user("manager@review.test").roles("CLUB_MANAGER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void approvedImageRemainsReadableThroughScopedHorseImageEndpoint() throws Exception {
    String path = "registrations/" + request + "/" + UUID.randomUUID() + ".png";
    db.update(
        "UPDATE horse_registration_requests SET horse_image_path=? WHERE id=?", path, request);
    var result = review(request, approval()).andReturn();
    String horse =
        json.readTree(result.getResponse().getContentAsString()).path("approved_horse_id").asText();
    mvc.perform(
            get("/api/horses/" + horse + "/image")
                .with(user("owner@review.test").roles("HORSE_OWNER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").exists());
    mvc.perform(
            get("/api/horses/" + horse + "/image")
                .with(user("other@review.test").roles("HORSE_OWNER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void swaggerIncludesReviewActionAndBothOperations() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/club-manager/horse-registration-requests'].get").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/club-manager/horse-registration-requests/{id}'].patch.security[0].bearerAuth")
                .isArray())
        .andExpect(
            jsonPath(
                    "$.components.schemas.ReviewHorseRegistrationRequest.properties.action.enum[0]")
                .value("APPROVE"));
  }
}
