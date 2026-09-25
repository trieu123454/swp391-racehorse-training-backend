package com.example.springbootbackend.horse.controller;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=","spring.datasource.url=jdbc:h2:mem:horses_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HorseControllerTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String box;
    @BeforeEach void setup() {
        for(String role:List.of("CLUB_MANAGER","HORSE_OWNER","HEAD_TRAINER","GROOM")) {
            db.update("INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT ?,?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",role,role+"@test.com",role);
        }
        box=UUID.randomUUID().toString();
        db.update("INSERT INTO stable_boxes(id,box_code,capacity) VALUES (?, 'A1',1)",box);
    }
    long owner() { return db.queryForObject("SELECT user_id FROM users WHERE email='HORSE_OWNER@test.com'",Long.class); }
    String body(Long owner) throws Exception { return json.writeValueAsString(Map.of("horseName","Thunder","stableBoxId",box,"ownerId",owner)); }
    String createHorse() throws Exception {
        var result=mvc.perform(post("/api/horses").with(user("CLUB_MANAGER@test.com")).contentType("application/json").content(body(owner())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.current_status").value("Healthy")).andExpect(jsonPath("$.is_training_locked").value(false)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    @Test void ownershipAndPermissions() throws Exception {
        String id=createHorse();
        mvc.perform(get("/api/horses/"+id).with(user("HORSE_OWNER@test.com"))).andExpect(status().isOk());
        db.update("UPDATE horses SET owner_id=NULL WHERE id=?",id);
        mvc.perform(get("/api/horses/"+id).with(user("HORSE_OWNER@test.com"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/horses/"+id+"/image").with(user("HORSE_OWNER@test.com"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/horses").with(user("HORSE_OWNER@test.com"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/horses").with(user("GROOM@test.com"))).andExpect(status().isOk());
        mvc.perform(get("/api/horses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/horses").with(user("HEAD_TRAINER@test.com")).contentType("application/json").content(body(owner()))).andExpect(status().isForbidden());
        db.update("UPDATE users SET status='PENDING' WHERE email='CLUB_MANAGER@test.com'");
        mvc.perform(get("/api/horses").with(user("CLUB_MANAGER@test.com"))).andExpect(status().isForbidden());
    }
    @Test void capacityAndUpdateProtectedFields() throws Exception {
        String id=createHorse();
        mvc.perform(post("/api/horses").with(user("CLUB_MANAGER@test.com")).contentType("application/json").content(body(owner()))).andExpect(status().isConflict());
        mvc.perform(get("/api/horses/options/stables").with(user("CLUB_MANAGER@test.com"))).andExpect(jsonPath("$.length()").value(5));
        String update=body(owner()).replace("Thunder","Lightning").replace("}",",\"currentStatus\":\"Injured\",\"isTrainingLocked\":true}");
        mvc.perform(put("/api/horses/"+id).with(user("CLUB_MANAGER@test.com")).contentType("application/json").content(update))
            .andExpect(status().isOk()).andExpect(jsonPath("$.horse_name").value("Lightning")).andExpect(jsonPath("$.current_status").value("Healthy")).andExpect(jsonPath("$.is_training_locked").value(false));
    }
    @Test void softDeleteRetainsHistoryAndRequiresConfirmation() throws Exception {
        String id=createHorse();
        db.update("INSERT INTO medical_records(id,horse_id,diagnosis) VALUES (?,?,'Injury')",UUID.randomUUID().toString(),id);
        mvc.perform(get("/api/horses/"+id+"/deletion-warnings").with(user("CLUB_MANAGER@test.com"))).andExpect(jsonPath("$.medicalRecords").value(1));
        mvc.perform(delete("/api/horses/"+id).with(user("CLUB_MANAGER@test.com"))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/horses/"+id+"?confirmed=true").with(user("CLUB_MANAGER@test.com"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/horses/"+id).with(user("CLUB_MANAGER@test.com"))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT count(*) FROM medical_records WHERE horse_id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM horses WHERE id=? AND deleted_at IS NOT NULL",Long.class,id)).isEqualTo(1);
    }
    @Test void validationOwnerConfirmationAndSearch() throws Exception {
        String id=createHorse();
        mvc.perform(get("/api/horses?search=thun&currentStatus=Healthy&isTrainingLocked=false").with(user("HEAD_TRAINER@test.com"))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/horses?search=thun&currentStatus=Injured").with(user("HEAD_TRAINER@test.com"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/horses?search=%25").with(user("HEAD_TRAINER@test.com"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(put("/api/horses/"+id).with(user("CLUB_MANAGER@test.com")).contentType("application/json").content("{\"horseName\":\"Thunder\",\"stableBoxId\":\""+box+"\"}" )).andExpect(status().isOk());
        mvc.perform(post("/api/horses").with(user("CLUB_MANAGER@test.com")).contentType("application/json").content(body(owner()).replace("Thunder","   "))).andExpect(status().isBadRequest());
    }
    @Test void invalidAndOversizeUploadsRejected() throws Exception {
        mvc.perform(multipart("/api/horses/images").file(new MockMultipartFile("file","bad.png","image/png","not an image".getBytes())).with(user("CLUB_MANAGER@test.com"))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/horses/images").file(new MockMultipartFile("file","big.png","image/png",new byte[5*1024*1024+1])).with(user("CLUB_MANAGER@test.com"))).andExpect(status().isPayloadTooLarge());
    }
}
