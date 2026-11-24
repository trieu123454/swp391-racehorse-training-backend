package com.example.springbootbackend.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.config.import=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.url=jdbc:h2:mem:owner_requests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class HorseOwnerRequestTests {
  static final String BASE = "/api/horse-owner/";
  static final String REG = BASE + "horse-registration-requests",
      TRANSFER = BASE + "ownership-transfer-requests";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired AppUserRepository users;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper json;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @org.springframework.boot.test.mock.mockito.MockBean
  com.example.springbootbackend.service.HorseStorage cloudinary;

  String horse;

  @BeforeEach
  void setup() {
    for (String email : List.of("owner@test.com", "recipient@test.com", "other@test.com"))
      addUser(email, "HORSE_OWNER");
    horse = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horses(id,horse_name,owner_id) VALUES (?,'Thunder',?)",
        horse,
        uid("owner@test.com"));
  }

  void addUser(String email, String role) {
    if (db.queryForObject("SELECT count(*) FROM roles WHERE role_name=?", Integer.class, role) == 0)
      db.update("INSERT INTO roles(role_name) VALUES (?)", role);
    db.update(
        "INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT"
            + " ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",
        email,
        email,
        role);
  }

  long uid(String email) {
    return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
    // Each real HTTP request gets fresh user state; do not reuse this test transaction's cached
    // entity.
    entityManager.clear();
    return request.header(
        "Authorization",
        "Bearer "
            + com.example.springbootbackend.TestTokens.create(
                jwt, db, users.findByEmailIgnoreCase(email).orElseThrow()));
  }

  MockHttpServletRequestBuilder body(
      MockHttpServletRequestBuilder request, Map<String, Object> input) throws Exception {
    return request.contentType("application/json").content(json.writeValueAsString(input));
  }

  Map<String, Object> transferBody() {
    return Map.of(
        "horse_id", horse, "new_owner_email", "recipient@test.com", "reason", "Transfer request");
  }

  @Test
  void bothListsAreEmptySuccessfulJsonForNewOwner() throws Exception {
    for (String path : List.of(REG, TRANSFER))
      mvc.perform(as(get(path), "owner@test.com"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$").isEmpty())
          .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void listsLegacyCloudinaryDocumentsAndInlineHistoryOnlyForTheirOwner() throws Exception {
    String id = UUID.randomUUID().toString();
    String path = "horse-documents/cloudinary/" + UUID.randomUUID() + ".pdf";
    org.mockito.Mockito.when(cloudinary.signedUrl(path))
        .thenReturn(Map.of("url", "https://res.cloudinary.com/demo/image/upload/proof.pdf"));
    db.update(
        "INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name,history_json)"
            + " VALUES (?,?,'REGISTER','Legacy horse',?)",
        id,
        uid("owner@test.com"),
        "[{\"action\":\"SUBMITTED\"}]");
    db.update(
        "INSERT INTO"
            + " horse_registration_documents(id,request_id,object_path,original_filename,content_type,file_size)"
            + " VALUES (?,?,?,'proof.pdf','application/pdf',100)",
        UUID.randomUUID().toString(),
        id,
        path);
    mvc.perform(as(get(REG), "other@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    org.mockito.Mockito.verifyNoInteractions(cloudinary);
    mvc.perform(as(get(REG), "owner@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(
            jsonPath("$[0].documents[0].url")
                .value("https://res.cloudinary.com/demo/image/upload/proof.pdf"))
        .andExpect(jsonPath("$[0].history[0].action").value("SUBMITTED"));
  }

  @Test
  void registrationIsPersistedPendingAndScopedToSender() throws Exception {
    var result =
        mvc.perform(
                as(
                    body(
                        post(REG),
                        Map.of(
                            "horse_name",
                            "New Horse",
                            "birth_year",
                            2020,
                            "requester_id",
                            uid("other@test.com"),
                            "status",
                            "Approved")),
                    "owner@test.com"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requester_id").value(uid("owner@test.com")))
            .andExpect(jsonPath("$.created_at").exists())
            .andReturn();
    String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    assertThat(
            db.queryForObject(
                "SELECT status FROM horse_registration_requests WHERE id=?", String.class, id))
        .isEqualTo("PENDING");
    assertThat(db.queryForObject("SELECT count(*) FROM horses", Long.class)).isEqualTo(1);
    mvc.perform(as(get(REG), "owner@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(as(get(REG), "other@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void jsonPatchAndLegacyEmptyArraysRemainCompatible() throws Exception {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO horse_registration_requests(id,owner_id,request_type,horse_name) VALUES"
            + " (?,?,'REGISTER','Legacy Horse')",
        id,
        uid("owner@test.com"));
    mvc.perform(as(get(REG + "/" + id), "owner@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documents").isEmpty())
        .andExpect(jsonPath("$.history").isEmpty());
    mvc.perform(
            as(
                body(
                    patch(REG + "/" + id),
                    Map.of("ownerNote", "Updated JSON", "microchipNumber", "123")),
                "owner@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.horse_name").value("Legacy Horse"))
        .andExpect(jsonPath("$.reason").value("Updated JSON"))
        .andExpect(jsonPath("$.microchip_number").value("123"))
        .andExpect(jsonPath("$.documents").isArray())
        .andExpect(jsonPath("$.history").isArray());
  }

  @Test
  void transferIsPendingWithoutChangingOwnerAndDuplicateIsConflict() throws Exception {
    mvc.perform(as(body(post(TRANSFER), transferBody()), "owner@test.com"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.new_owner_email").value("recipient@test.com"));
    assertThat(db.queryForObject("SELECT owner_id FROM horses WHERE id=?", Long.class, horse))
        .isEqualTo(uid("owner@test.com"));
    mvc.perform(as(get(TRANSFER), "owner@test.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    for (String email : List.of("recipient@test.com", "other@test.com"))
      mvc.perform(as(get(TRANSFER), email))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(body(post(TRANSFER), transferBody()), "owner@test.com"))
        .andExpect(status().isConflict());
  }

  @Test
  void transferChecksOwnershipRecipientAndHorseExistence() throws Exception {
    mvc.perform(as(body(post(TRANSFER), transferBody()), "other@test.com"))
        .andExpect(status().isForbidden());
    mvc.perform(
            as(
                body(post(TRANSFER), Map.of("horseId", horse, "newOwnerEmail", "owner@test.com")),
                "owner@test.com"))
        .andExpect(status().isBadRequest());
    db.update("UPDATE users SET status='LOCKED' WHERE email='recipient@test.com'");
    mvc.perform(as(body(post(TRANSFER), transferBody()), "owner@test.com"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            as(
                body(
                    post(TRANSFER),
                    Map.of("horse_id", UUID.randomUUID(), "new_owner_email", "recipient@test.com")),
                "owner@test.com"))
        .andExpect(status().isNotFound());
    db.update("UPDATE horses SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", horse);
    mvc.perform(as(body(post(TRANSFER), transferBody()), "owner@test.com"))
        .andExpect(status().isNotFound());
    assertThat(
            db.queryForObject("SELECT count(*) FROM owner_ownership_transfer_requests", Long.class))
        .isZero();
  }

  @Test
  void validatesFormsAndAcceptsCamelCase() throws Exception {
    mvc.perform(as(body(post(REG), Map.of("horseName", "Camel Horse")), "owner@test.com"))
        .andExpect(status().isCreated());
    mvc.perform(as(body(post(REG), Map.of("horse_name", " ")), "owner@test.com"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isArray());
    mvc.perform(
            as(
                body(post(REG), Map.of("horse_name", "Horse", "birth_year", 9999)),
                "owner@test.com"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            as(
                body(post(TRANSFER), Map.of("horse_id", horse, "new_owner_email", "invalid")),
                "owner@test.com"))
        .andExpect(status().isBadRequest());
    mvc.perform(as(body(post(TRANSFER), Map.of()), "owner@test.com"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            as(
                body(
                    post(TRANSFER),
                    Map.of("horseId", horse, "newOwnerEmail", "recipient@test.com")),
                "owner@test.com"))
        .andExpect(status().isCreated());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"GROOM", "HEAD_TRAINER", "VETERINARIAN", "HEAD_VETERINARIAN", "CLUB_MANAGER"})
  void unrelatedRolesCannotListOrCreate(String role) throws Exception {
    addUser("staff@test.com", role);
    for (String path : List.of(REG, TRANSFER)) {
      mvc.perform(as(get(path), "staff@test.com")).andExpect(status().isForbidden());
      mvc.perform(
              as(
                  body(
                      post(path),
                      path.equals(REG) ? Map.of("horse_name", "Horse") : transferBody()),
                  "staff@test.com"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void authenticationAndAccountRestrictionsApplyToListsAndForms() throws Exception {
    for (String path : List.of(REG, TRANSFER)) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(body(post(path), Map.of())).andExpect(status().isUnauthorized());
    }
    db.update("UPDATE users SET must_change_password=TRUE WHERE email='owner@test.com'");
    for (String path : List.of(REG, TRANSFER)) {
      mvc.perform(as(get(path), "owner@test.com")).andExpect(status().isForbidden());
      mvc.perform(
              as(
                  body(
                      post(path),
                      path.equals(REG) ? Map.of("horse_name", "Horse") : transferBody()),
                  "owner@test.com"))
          .andExpect(status().isForbidden());
    }
    db.update(
        "UPDATE users SET must_change_password=FALSE,status='LOCKED' WHERE email='owner@test.com'");
    for (String path : List.of(REG, TRANSFER))
      mvc.perform(as(get(path), "owner@test.com")).andExpect(status().isUnauthorized());
  }
}
