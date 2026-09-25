package com.example.springbootbackend.clubmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:club_manager_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
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
    }

    @Test
    void pendingListExcludesHorseOwnersAndSupportsRoleFilter() throws Exception {
        mvc.perform(get("/api/club-manager/users/pending")
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
        mvc.perform(patch("/api/club-manager/users/{id}/approve", trainerId).with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Long.class, trainerId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed=?", Long.class, "APPROVE_USER: " + trainerId)).isEqualTo(1);

        mvc.perform(patch("/api/club-manager/users/{id}/approve", trainerId).with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isConflict());
    }

    @Test
    void managerCannotLockSelfOrAnotherManager() throws Exception {
        long managerId = userId("manager@test.com");
        mvc.perform(patch("/api/club-manager/users/{id}/lock", managerId)
                        .with(user("manager@test.com").roles("CLUB_MANAGER"))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "test"))))
                .andExpect(status().isForbidden());
    }

    private void createUser(String name, String email, String role, String status) {
        Integer roleId = db.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Integer.class, role);
        db.update("INSERT INTO users(full_name,email,password_hash,role_id,status) VALUES (?,?,?, ?,?)",
                name, email, "hash", roleId, status);
    }

    private long userId(String email) {
        return db.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
    }
}
