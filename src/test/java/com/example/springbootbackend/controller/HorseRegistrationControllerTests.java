package com.example.springbootbackend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.springbootbackend.service.HorseStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:horse_registration_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HorseRegistrationControllerTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean HorseStorage storage;

    private String managerEmail;
    private String ownerEmail;
    private String otherOwnerEmail;
    private String trainerEmail;
    private String stableId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        managerEmail = "manager-" + suffix + "@test.com";
        ownerEmail = "owner-" + suffix + "@test.com";
        otherOwnerEmail = "other-" + suffix + "@test.com";
        trainerEmail = "trainer-" + suffix + "@test.com";
        addUser("CLUB_MANAGER", managerEmail, "Manager " + suffix);
        addUser("HORSE_OWNER", ownerEmail, "Owner " + suffix);
        addUser("HORSE_OWNER", otherOwnerEmail, "Other Owner " + suffix);
        addUser("HEAD_TRAINER", trainerEmail, "Trainer " + suffix);
        stableId = UUID.randomUUID().toString();
        db.update("INSERT INTO stable_boxes(id,box_code,section,capacity,status) VALUES (?,?,?,10,'Available')",
                stableId, "T" + suffix, "A");

        when(storage.uploadRegistrationHorseImage(any(), anyLong())).thenReturn("horses/" + UUID.randomUUID() + ".jpg");
        when(storage.uploadRegistrationDocument(any())).thenAnswer(invocation ->
                "horse-documents/" + UUID.randomUUID() + ".pdf");
        when(storage.signedUrl(anyString())).thenReturn(Map.of(
                "hasImage", true, "url", "https://storage.test/signed", "expiresIn", 300));
    }

    @Test
    void ownerSubmitsManagerRequestsInformationAndOwnerResubmitsWithHistory() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", "CHIP-101", "PASS-101"),
                        List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision_no").value(1))
                .andExpect(jsonPath("$.documents[0].document_type").value("OWNERSHIP_PROOF"));
        String requestId = latestRequestId(ownerEmail);

        review(requestId, "REQUEST_INFORMATION", "Vui lòng bổ sung giấy chứng nhận ngựa.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_INFORMATION"));

        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "River Song", "CHIP-101", "PASS-101")))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision_no").value(2))
                .andExpect(jsonPath("$.history.length()").value(3));

        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "River Song", "CHIP-101", "PASS-101")))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/horse-owner/horse-registration-requests")
                        .with(user(otherOwnerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void rejectsMissingSupportingFileInvalidValuesDuplicateIdentifiersAndWrongRoles() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", "CHIP-201", null),
                        List.of()))
                .andExpect(status().isBadRequest());

        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", "CHIP-201", null)
                                .replace("2020", "2200"), List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isBadRequest());

        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", "CHIP-201", null),
                        List.of("HORSE_CERTIFICATE")))
                .andExpect(status().isCreated());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Another Horse", "chip-201", null),
                        List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isConflict());

        mvc.perform(submit(trainerEmail, "HEAD_TRAINER", submission("REGISTER", "Unauthorized", null, null),
                        List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/horse-owner/horse-registration-requests")
                        .with(user(trainerEmail).roles("HEAD_TRAINER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerApprovalCreatesProfileAndPersistsIdentityPedigreeAndDocuments() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER",
                        submission("REGISTER", "River Song", "CHIP-301", "PASS-301"), List.of("OWNERSHIP_PROOF", "HEALTH_DOCUMENT")))
                .andExpect(status().isCreated());
        String requestId = latestRequestId(ownerEmail);

        mvc.perform(get("/api/club-manager/horse-registration-requests")
                        .with(user(managerEmail).roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documents.length()").value(2))
                .andExpect(jsonPath("$[0].owner_email").value(ownerEmail));

        String horseId = approveRegister(requestId);
        assertThat(db.queryForObject("SELECT microchip_number FROM horses WHERE id=?", String.class, horseId)).isEqualTo("CHIP-301");
        assertThat(db.queryForObject("SELECT passport_number FROM horses WHERE id=?", String.class, horseId)).isEqualTo("PASS-301");
        assertThat(db.queryForObject("SELECT pedigree_father FROM horses WHERE id=?", String.class, horseId)).isEqualTo("North Star");
        assertThat(db.queryForObject("SELECT count(*) FROM horse_ownership_history WHERE horse_id=?", Integer.class, horseId)).isEqualTo(1);

        Map<String, Object> profileEdit = new java.util.LinkedHashMap<>();
        profileEdit.put("horseName", "River Song");
        profileEdit.put("breed", "Thoroughbred");
        profileEdit.put("birthYear", 2020);
        profileEdit.put("heightCm", 160.0);
        profileEdit.put("currentWeightKg", 450.0);
        profileEdit.put("pedigreeFather", "Updated Sire");
        profileEdit.put("pedigreeMother", "Updated Dam");
        profileEdit.put("microchipNumber", "CHIP-301");
        profileEdit.put("passportNumber", "PASS-301");
        profileEdit.put("stableBoxId", stableId);
        profileEdit.put("ownerId", ownerId(ownerEmail));
        mvc.perform(put("/api/horses/{id}", horseId)
                        .with(user(managerEmail).roles("CLUB_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(profileEdit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedigree_father").value("Updated Sire"))
                .andExpect(jsonPath("$.pedigree_mother").value("Updated Dam"));
        profileEdit.put("ownerId", ownerId(otherOwnerEmail));
        mvc.perform(put("/api/horses/{id}", horseId)
                        .with(user(managerEmail).roles("CLUB_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(profileEdit)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/horses/{id}", horseId)
                        .with(user(ownerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(profileEdit)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/horses/{id}", horseId).with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedigree_father").value("Updated Sire"))
                .andExpect(jsonPath("$.microchip_number").value("CHIP-301"))
                .andExpect(jsonPath("$.passport_number").value("PASS-301"))
                .andExpect(jsonPath("$.registration_documents[0].document_type").exists());
        mvc.perform(get("/api/horses/{id}", horseId).with(user(otherOwnerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isForbidden());

        mvc.perform(submit(otherOwnerEmail, "HORSE_OWNER",
                        submission("REGISTER", "Duplicate Horse", "chip-301", null), List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/horses").with(user(managerEmail).roles("CLUB_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("horseName", "Bypass", "stableBoxId", stableId,
                                "ownerId", ownerId(ownerEmail)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void transferNeedsCurrentOwnerConsentAndConsentDeclinePreservesCurrentOwner() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER",
                        submission("REGISTER", "River Song", "CHIP-401", "PASS-401"), List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isCreated());
        String initialRequestId = latestRequestId(ownerEmail);
        String horseId = approveRegister(initialRequestId);

        mvc.perform(submit(otherOwnerEmail, "HORSE_OWNER",
                        submission("LINK", "River Song", "CHIP-401", "PASS-401"), List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isCreated());
        String transferRequestId = latestRequestId(otherOwnerEmail);
        review(transferRequestId, "APPROVE", null, "horseId", horseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_OWNER_CONSENT"));
        assertThat(ownerIdFromHorse(horseId)).isEqualTo(ownerId(ownerEmail));

        mvc.perform(get("/api/horse-owner/ownership-transfer-requests")
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].horse_id").value(horseId));
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", transferRequestId)
                        .with(user(otherOwnerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", transferRequestId)
                        .with(user(ownerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(ownerIdFromHorse(horseId)).isEqualTo(ownerId(otherOwnerEmail));
        mvc.perform(get("/api/horses/{id}", horseId).with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isForbidden());

        mvc.perform(submit(ownerEmail, "HORSE_OWNER",
                        submission("LINK", "River Song", "CHIP-401", "PASS-401"), List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isCreated());
        String declinedRequestId = latestRequestId(ownerEmail);
        review(declinedRequestId, "APPROVE", null, "horseId", horseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_OWNER_CONSENT"));
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", declinedRequestId)
                        .with(user(otherOwnerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "DECLINE", "note", "Chưa thống nhất."))))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT status FROM horse_registration_requests WHERE id=?", String.class, declinedRequestId))
                .isEqualTo("REJECTED");
        assertThat(ownerIdFromHorse(horseId)).isEqualTo(ownerId(otherOwnerEmail));
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", declinedRequestId)
                        .with(user(otherOwnerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectionRequiresReasonAndCannotBeReapproved() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", null, null),
                        List.of("OWNERSHIP_PROOF"))).andExpect(status().isCreated());
        String id = latestRequestId(ownerEmail);
        review(id, "REJECT", null).andExpect(status().isBadRequest());
        review(id, "REJECT", "Không đủ giấy tờ.").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        review(id, "APPROVE", null, "stableBoxId", stableId).andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(
            String email, String role, String requestJson, List<String> documentTypes) throws Exception {
        var builder = multipart("/api/horse-owner/horse-registration-requests")
                .file(requestPart(requestJson));
        for (int index = 0; index < documentTypes.size(); index++) {
            builder.file(new MockMultipartFile("documents", "proof-" + index + ".pdf", "application/pdf",
                    "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII)));
        }
        return builder.file(new MockMultipartFile("documentTypes", "types.json", "application/json",
                        json.writeValueAsBytes(documentTypes)))
                .with(user(email).roles(role));
    }

    private MockMultipartFile requestPart(String requestJson) {
        return new MockMultipartFile("request", "request.json", "application/json",
                requestJson.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions review(String id, String action, String note,
            String selectionKey, String selectionValue) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", action);
        if (note != null) body.put("reviewNote", note);
        if (selectionKey != null) body.put(selectionKey, selectionValue);
        return mvc.perform(patch("/api/club-manager/horse-registration-requests/{id}", id)
                .with(user(managerEmail).roles("CLUB_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }

    private org.springframework.test.web.servlet.ResultActions review(String id, String action, String note) throws Exception {
        return review(id, action, note, null, null);
    }

    private String approveRegister(String requestId) throws Exception {
        String response = review(requestId, "APPROVE", "Đã kiểm tra giấy tờ.", "stableBoxId", stableId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.path("approved_horse_id").asText();
    }

    private String latestRequestId(String email) {
        return db.queryForObject("SELECT id FROM horse_registration_requests WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                String.class, ownerId(email));
    }

    private long ownerId(String email) {
        return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
    }

    private long ownerIdFromHorse(String horseId) {
        return db.queryForObject("SELECT owner_id FROM horses WHERE id=?", Long.class, horseId);
    }

    private void addUser(String role, String email, String name) {
        db.update("INSERT INTO users(full_name,email,password_hash,role_id,status) "
                        + "SELECT ?,?,'test-hash',role_id,'APPROVED' FROM roles WHERE role_name=?",
                name, email, role);
    }

    private static String submission(String type, String name, String chip, String passport) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestType", type);
        body.put("horseName", name);
        body.put("breed", "Thoroughbred");
        body.put("birthYear", 2020);
        body.put("heightCm", 162.5);
        body.put("currentWeightKg", 450.0);
        body.put("pedigreeFather", "North Star");
        body.put("pedigreeMother", "River Pearl");
        body.put("microchipNumber", chip);
        body.put("passportNumber", passport);
        body.put("ownerNote", "Tài liệu đã đính kèm.");
        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
