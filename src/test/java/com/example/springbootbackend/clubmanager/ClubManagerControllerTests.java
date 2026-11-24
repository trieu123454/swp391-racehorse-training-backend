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

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.url=jdbc:h2:mem:club_manager_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
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
    void inventoryTransactionCatalogAndAllSupplyRequestsRespectContractAndPermissions() throws Exception {
        String item=java.util.UUID.randomUUID().toString();
        db.update("INSERT INTO inventory_items(id,item_name,item_code) VALUES (?,'Hay','HAY')",item);
        long actor=userId("manager@test.com"),groom=userId("groom@test.com");
        String path="/api/club-manager/inventory-transactions";
        mvc.perform(get(path).with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        db.update("INSERT INTO inventory_transactions(id,item_id,actor_id,transaction_type,quantity_change,quantity_before,quantity_after) VALUES (?,?,?,'RECEIPT',10,0,10)",
                java.util.UUID.randomUUID().toString(),item,actor);
        mvc.perform(get(path+"?limit=100").with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].item_code").value("HAY"))
                .andExpect(jsonPath("$.data[0].quantity_after").value(10)).andExpect(jsonPath("$.data[0].lot_number").isEmpty());
        mvc.perform(get(path+"?limit=0").with(user("manager@test.com").roles("CLUB_MANAGER"))).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(user("groom@test.com").roles("GROOM"))).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for(String state:java.util.List.of("Pending","Approved","Rejected","PartiallyApproved"))
            db.update("INSERT INTO supply_requests(id,requested_by,item_id,quantity_requested,status) VALUES (?,?,?,1,?)",java.util.UUID.randomUUID().toString(),groom,item,state);
        mvc.perform(get("/api/club-manager/supply-requests?status=All").with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(get("/api/club-manager/supply-requests?status=Pending").with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/club-manager/supply-requests?status=invalid").with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isBadRequest());
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

    @Test
    void lockedAccountsRemainListedAndCanBeUnlocked() throws Exception {
        long lockedGroomId = userId("locked@test.com");
        mvc.perform(get("/api/club-manager/users")
                        .with(user("manager@test.com").roles("CLUB_MANAGER"))
                        .param("status", "LOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(lockedGroomId))
                .andExpect(jsonPath("$[0].status").value("LOCKED"));

        mvc.perform(patch("/api/club-manager/users/{id}/unlock", lockedGroomId)
                        .with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(db.queryForObject("SELECT status FROM users WHERE user_id=?", String.class, lockedGroomId)).isEqualTo("APPROVED");
        assertThat(db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Long.class, lockedGroomId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE action_performed=?", Long.class, "UNLOCK_USER: " + lockedGroomId)).isEqualTo(1);

        mvc.perform(patch("/api/club-manager/users/{id}/unlock", lockedGroomId)
                        .with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isConflict());
    }

    @Test
    void managerCannotUnlockAnotherClubManager() throws Exception {
        createUser("Locked Manager", "locked-manager@test.com", "CLUB_MANAGER", "LOCKED");
        long managerId = userId("locked-manager@test.com");

        mvc.perform(patch("/api/club-manager/users/{id}/unlock", managerId)
                        .with(user("manager@test.com").roles("CLUB_MANAGER")))
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

    @Test void structuredAccountNotificationsCaptureRoleTransitionAndOptionalLockReason() throws Exception {
        long groom=userId("groom@test.com");
        mvc.perform(patch("/api/club-manager/users/{id}/role",groom).with(user("manager@test.com").roles("CLUB_MANAGER"))
                .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("roleName","VETERINARIAN"))))
                .andExpect(status().isOk());
        var role=db.queryForMap("SELECT type,params,message FROM notifications WHERE user_id=?",groom);
        assertThat(role.get("type")).isEqualTo("ROLE_CHANGED");
        assertThat(role.get("message")).isEqualTo("");
        assertThat(json.readTree(role.get("params").toString()).get("old_role").asText()).isEqualTo("GROOM");
        assertThat(json.readTree(role.get("params").toString()).get("new_role").asText()).isEqualTo("VETERINARIAN");
        mvc.perform(patch("/api/club-manager/users/{id}/lock",groom).with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk());
        String lock=db.queryForObject("SELECT params FROM notifications WHERE user_id=? AND type='ACCOUNT_LOCKED'",String.class,groom);
        assertThat(json.readTree(lock).has("reason")).isFalse();
        mvc.perform(patch("/api/club-manager/users/{id}/unlock",groom).with(user("manager@test.com").roles("CLUB_MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notifications").with(user("groom@test.com").roles("VETERINARIAN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread_count").value(3));
        assertThat(db.queryForObject("SELECT title FROM notifications WHERE user_id=? AND type='ACCOUNT_UNLOCKED'",String.class,groom))
                .isEqualTo("Tài khoản đã được mở khóa");
    }

}
