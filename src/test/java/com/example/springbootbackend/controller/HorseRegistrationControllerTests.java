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
import jakarta.persistence.EntityManager;
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
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @MockBean HorseStorage storage;

    private String managerEmail;
    private String ownerEmail;
    private String otherOwnerEmail;
    private String trainerEmail;
    private String stableId;
    private static final List<String> REGISTER_DOCS = List.of("OWNERSHIP_PROOF", "HEALTH_DOCUMENT");

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
                        REGISTER_DOCS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision_no").value(1))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[*].document_type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("OWNERSHIP_PROOF", "HEALTH_DOCUMENT")));
        String requestId = latestRequestId(ownerEmail);

        review(requestId, "REQUEST_INFORMATION", "Vui lòng bổ sung giấy chứng nhận ngựa.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_INFORMATION"));

        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "River Song", "CHIP-101", "PASS-101")))
                        .file(new MockMultipartFile("documents", "cert.pdf", "application/pdf",
                                "%PDF-1.7 cert".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documentTypes", "types.json", "application/json",
                                json.writeValueAsBytes(List.of("HORSE_CERTIFICATE"))))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision_no").value(2))
                .andExpect(jsonPath("$.documents.length()").value(3))
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
                                .replace("2020", "2200"), REGISTER_DOCS))
                .andExpect(status().isBadRequest());

        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "River Song", "CHIP-201", null),
                        REGISTER_DOCS))
                .andExpect(status().isCreated());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Another Horse", "chip-201", null),
                        REGISTER_DOCS))
                .andExpect(status().isConflict());

        mvc.perform(submit(trainerEmail, "HEAD_TRAINER", submission("REGISTER", "Unauthorized", null, null),
                        REGISTER_DOCS))
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
        assertThat(db.queryForObject("SELECT ownership_history_json FROM horses WHERE id=?", String.class, horseId)).contains("REGISTRATION_APPROVAL");

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
                        submission("REGISTER", "Duplicate Horse", "chip-301", null), REGISTER_DOCS))
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
                        submission("REGISTER", "River Song", "CHIP-401", "PASS-401"), REGISTER_DOCS))
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
                        REGISTER_DOCS)).andExpect(status().isCreated());
        String id = latestRequestId(ownerEmail);
        review(id, "REJECT", null).andExpect(status().isBadRequest());
        review(id, "REJECT", "Không đủ giấy tờ.").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        review(id, "APPROVE", null, "stableBoxId", stableId).andExpect(status().isConflict());
    }

    @Test
    void rejectsQuarantineStableAllowsDeletedHorseReRegistrationAndHandlesConsentErrors() throws Exception {
        String quarantineBox = UUID.randomUUID().toString();
        db.update("INSERT INTO stable_boxes(id,box_code,section,capacity,status,box_type) VALUES (?,?,?,1,'Available','Quarantine')",
                quarantineBox, "Q" + UUID.randomUUID().toString().substring(0, 6), "Khu cách ly");

        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Star Runner", "CHIP-501", "PASS-501"),
                        REGISTER_DOCS)).andExpect(status().isCreated());
        String requestId = latestRequestId(ownerEmail);

        // Manager receives notification on submission and sees pending count
        mvc.perform(get("/api/club-manager/horse-registration-requests/pending-count")
                        .with(user(managerEmail).roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND related_id=?",
                Long.class, ownerId(managerEmail), requestId)).isEqualTo(1L);

        // Duplicate active REGISTER request blocked
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "star runner", null, null),
                        REGISTER_DOCS)).andExpect(status().isConflict());

        // Cannot assign Quarantine box on REGISTER approval
        review(requestId, "APPROVE", null, "stableBoxId", quarantineBox)
                .andExpect(status().isBadRequest());

        String horseId = approveRegister(requestId);
        // Same owner cannot LINK own horse
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("LINK", "Star Runner", "CHIP-501", "PASS-501"),
                        List.of("OWNERSHIP_PROOF"))).andExpect(status().isCreated());
        String selfLinkId = latestRequestId(ownerEmail);
        review(selfLinkId, "APPROVE", null, "horseId", horseId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        // Other owner links horse without image -> consent copies image and HorseExceptionHandler formats errors
        db.update("UPDATE horses SET image_url=NULL WHERE id=?", horseId);
        entityManager.flush();
        entityManager.clear();
        var linkMultipart = multipart("/api/horse-owner/horse-registration-requests")
                .file(requestPart(submission("LINK", "Star Runner", "CHIP-501", "PASS-501")))
                .file(new MockMultipartFile("horseImage", "photo.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .file(new MockMultipartFile("documents", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .file(new MockMultipartFile("documentTypes", "types.json", "application/json", json.writeValueAsBytes(List.of("OWNERSHIP_PROOF"))))
                .with(user(otherOwnerEmail).roles("HORSE_OWNER"));
        mvc.perform(linkMultipart).andExpect(status().isCreated());
        String linkId = latestRequestId(otherOwnerEmail);
        review(linkId, "APPROVE", null, "horseId", horseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_OWNER_CONSENT"));

        // HorseExceptionHandler covers HorseOwnershipConsentController
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", linkId)
                        .with(user(otherOwnerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());

        // Locked requester cannot receive ownership on consent
        db.update("UPDATE users SET status='LOCKED' WHERE email=?", otherOwnerEmail);
        entityManager.flush();
        entityManager.clear();
        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", linkId)
                        .with(user(ownerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isConflict());
        db.update("UPDATE users SET status='APPROVED' WHERE email=?", otherOwnerEmail);
        entityManager.flush();
        entityManager.clear();

        mvc.perform(patch("/api/horse-owner/ownership-transfer-requests/{id}/consent", linkId)
                        .with(user(ownerEmail).roles("HORSE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("action", "CONSENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(db.queryForObject("SELECT image_url FROM horses WHERE id=?", String.class, horseId)).isNotNull();

        // Soft-deleting horse releases microchip/passport for new registration
        db.update("UPDATE horses SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", horseId);
        entityManager.flush();
        entityManager.clear();
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Star Runner Reborn", "CHIP-501", "PASS-501"),
                        REGISTER_DOCS)).andExpect(status().isCreated());
    }

    @Test
    void managerStatusFilterRemoveImageOnResubmitAndFreshDocumentUrls() throws Exception {
        var submitReq = multipart("/api/horse-owner/horse-registration-requests")
                .file(requestPart(submission("REGISTER", "Silver Bolt", "CHIP-601", "PASS-601")))
                .file(new MockMultipartFile("horseImage", "photo.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .file(new MockMultipartFile("documents", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .file(new MockMultipartFile("documents", "health.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .file(new MockMultipartFile("documentTypes", "types.json", "application/json", json.writeValueAsBytes(REGISTER_DOCS)))
                .with(user(ownerEmail).roles("HORSE_OWNER"));
        String createdJson = mvc.perform(submitReq).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(createdJson);
        String requestId = created.path("id").asText();
        String docId = created.path("documents").get(0).path("id").asText();

        when(storage.signedUrlFresh(anyString(), any())).thenReturn(Map.of("hasImage", true, "url", "https://storage.test/fresh", "expiresIn", 300));
        mvc.perform(get("/api/horse-owner/horse-registration-requests/{reqId}/documents/{docId}/url", requestId, docId)
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://storage.test/fresh"));
        mvc.perform(get("/api/club-manager/horse-registration-requests/{reqId}/documents/{docId}/url", requestId, docId)
                        .with(user(managerEmail).roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://storage.test/fresh"));

        review(requestId, "REQUEST_INFORMATION", "Cần xóa ảnh cũ.").andExpect(status().isOk());
        mvc.perform(get("/api/club-manager/horse-registration-requests?status=NEEDS_INFORMATION")
                        .with(user(managerEmail).roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId));

        String resubmitBody = submission("REGISTER", "Silver Bolt", "CHIP-601", "PASS-601")
                .replace("}", ",\"removeHorseImage\":true}");
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(resubmitBody))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horse_image_url").isEmpty());
    }

    @Test
    void requiresOwnershipAndHealthForRegisterAndOwnershipForLink() throws Exception {
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Missing Health", null, null),
                        List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Missing Ownership", null, null),
                        List.of("HEALTH_DOCUMENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Only Certificate", null, null),
                        List.of("HORSE_CERTIFICATE")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/horse-owner/horse-registration-requests")
                        .file(requestPart(submission("REGISTER", "Omitted Types", null, null)))
                        .file(new MockMultipartFile("documents", "doc1.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documents", "doc2.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("LINK", "Link Without Ownership", null, null),
                        List.of("HEALTH_DOCUMENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("LINK", "Link With Ownership", null, null),
                        List.of("OWNERSHIP_PROOF")))
                .andExpect(status().isCreated());

        mvc.perform(submit(ownerEmail, "HORSE_OWNER", submission("REGISTER", "Resubmit Optional Cert", null, null),
                        REGISTER_DOCS))
                .andExpect(status().isCreated());
        String requestId = latestRequestId(ownerEmail);
        review(requestId, "REQUEST_INFORMATION", "Bổ sung giấy chứng nhận giống.")
                .andExpect(status().isOk());
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "Resubmit Optional Cert", null, null)))
                        .file(new MockMultipartFile("documents", "cert.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documentTypes", "types.json", "application/json",
                                json.writeValueAsBytes(List.of("HORSE_CERTIFICATE"))))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision_no").value(2));
    }

    @Test
    void resubmitAllowsReplacingDocumentsBlocksEmptyResubmitAndEnforcesRequiredDocs() throws Exception {
        String createdJson = mvc.perform(submit(ownerEmail, "HORSE_OWNER",
                        submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701"), REGISTER_DOCS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(createdJson);
        String requestId = created.path("id").asText();
        String ownershipDocId = "";
        for (JsonNode doc : created.path("documents")) {
            if ("OWNERSHIP_PROOF".equals(doc.path("document_type").asText())) {
                ownershipDocId = doc.path("id").asText();
                break;
            }
        }
        String removedObjectPath = db.queryForObject(
                "SELECT object_path FROM horse_registration_documents WHERE id=?", String.class, ownershipDocId);

        review(requestId, "REQUEST_INFORMATION", "Ảnh giấy tờ quyền sở hữu bị mờ, vui lòng gửi lại.")
                .andExpect(status().isOk());

        // Empty resubmit with no text, image, or document changes -> 400
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701")))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isBadRequest());

        // Removing required OWNERSHIP_PROOF without replacement -> 400 and preserves both docs
        String removeWithoutReplace = submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701")
                .replace("}", ",\"removeDocumentIds\":[\"" + ownershipDocId + "\"]}");
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(removeWithoutReplace))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject(
                "SELECT count(*) FROM horse_registration_documents WHERE request_id=?", Long.class, requestId))
                .isEqualTo(2L);

        // Removing a non-existent/foreign document UUID -> 400
        String removeForeignDoc = submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701")
                .replace("}", ",\"removeDocumentIds\":[\"" + UUID.randomUUID() + "\"]}");
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(removeForeignDoc))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isBadRequest());

        // Exceeding 5 active documents (2 remaining + 4 new = 6) -> 400
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701")))
                        .file(new MockMultipartFile("documents", "extra1.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documents", "extra2.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documents", "extra3.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documents", "extra4.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documentTypes", "types.json", "application/json",
                                json.writeValueAsBytes(List.of("OTHER", "OTHER", "OTHER", "OTHER"))))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isBadRequest());

        // Replacing OWNERSHIP_PROOF -> 200 OK, old doc removed & deleted quietly from storage
        String replacedJson = mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(removeWithoutReplace))
                        .file(new MockMultipartFile("documents", "ownership-v2.pdf", "application/pdf",
                                "%PDF-1.7 v2".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("documentTypes", "types.json", "application/json",
                                json.writeValueAsBytes(List.of("OWNERSHIP_PROOF"))))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(2))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode replacedDocs = json.readTree(replacedJson).path("documents");
        assertThat(replacedDocs.findValuesAsText("id")).doesNotContain(ownershipDocId);
        org.mockito.Mockito.verify(storage).deleteQuietly(removedObjectPath);

        // Text-only correction on next NEEDS_INFORMATION -> 200 OK
        review(requestId, "REQUEST_INFORMATION", "Vui lòng cập nhật ghi chú.")
                .andExpect(status().isOk());
        String updatedTextBody = submission("REGISTER", "Doc Replace Horse", "CHIP-701", "PASS-701")
                .replace("Tài liệu đã đính kèm.", "Đã bổ sung bản scan rõ nét.");
        mvc.perform(multipart(HttpMethod.PATCH, "/api/horse-owner/horse-registration-requests/{id}", requestId)
                        .file(requestPart(updatedTextBody))
                        .with(user(ownerEmail).roles("HORSE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(3))
                .andExpect(jsonPath("$.documents.length()").value(2));
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
