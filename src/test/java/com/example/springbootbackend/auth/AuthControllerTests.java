package com.example.springbootbackend.auth;

import com.example.springbootbackend.auth.entity.AppUser;
import com.example.springbootbackend.auth.entity.UserStatus;
import com.example.springbootbackend.auth.repository.AppUserRepository;
import com.example.springbootbackend.auth.repository.RefreshTokenRepository;
import com.example.springbootbackend.auth.repository.RoleRepository;
import com.example.springbootbackend.auth.service.GoogleTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockBean private GoogleTokenService google;

    @BeforeEach
    void resetUsers() {
        tokens.deleteAll();
        for (AppUser user : users.findAll()) {
            user.approve(null);
            users.saveAndFlush(user);
        }
        users.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HORSE_OWNER"})
    void horseOwnerCanRegisterLoginAndRefresh(String role) throws Exception {
        register(role).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        var result = login().andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).get("refreshToken").asText();
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("APPROVED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER"})
    void staffMustWaitForApprovalEvenWhenFirstAccount(String role) throws Exception {
        register(role).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        login().andExpect(status().isBadRequest()).andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER"})
    void approvedManagerCanApprovePendingStaff(String role) throws Exception {
        AppUser approver = seed("CLUB_MANAGER", "approver@example.com");
        approver.approve(null);
        users.save(approver);
        String auth = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"approver@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        register(role).andExpect(jsonPath("$.status").value("PENDING"));
        Long id = users.findByEmailIgnoreCase("user@example.com").orElseThrow().getId();
        mockMvc.perform(post("/api/auth/users/" + id + "/approve")
                .header("Authorization", "Bearer " + json.readTree(auth).get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        login().andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HORSE_OWNER"})
    void existingPendingHorseOwnerCanLogin(String role) throws Exception {
        seed(role, "user@example.com");
        login().andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("APPROVED"));
        assertThat(users.findByEmailIgnoreCase("user@example.com").orElseThrow().getStatus())
                .isEqualTo(UserStatus.APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER", "CLUB_MANAGER"})
    void rejectedAndLockedAccountsRemainBlocked(String role) throws Exception {
        AppUser user = seed(role, "user@example.com");
        user.reject(null);
        users.save(user);
        login().andExpect(status().isBadRequest());
        user.lock();
        users.save(user);
        login().andExpect(status().isBadRequest());
        when(google.verify("valid-token")).thenReturn(new GoogleTokenService.GoogleProfile("user@example.com", "User"));
        googleLogin(role).andExpect(status().isBadRequest());
        assertThat(users.findByEmailIgnoreCase("user@example.com").orElseThrow().getStatus()).isEqualTo(UserStatus.LOCKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HORSE_OWNER"})
    void googleHorseOwnerCanLoginImmediately(String role) throws Exception {
        when(google.verify("valid-token")).thenReturn(new GoogleTokenService.GoogleProfile("user@example.com", "User"));
        googleLogin(role).andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("APPROVED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER"})
    void googlePendingStaffIsPersistedAndCannotChangeRoleToBypassApproval(String role) throws Exception {
        when(google.verify("valid-token")).thenReturn(new GoogleTokenService.GoogleProfile("user@example.com", "User"));
        googleLogin(role).andExpect(status().isBadRequest());
        AppUser manager = users.findByEmailIgnoreCase("user@example.com").orElseThrow();
        assertThat(manager.getStatus()).isEqualTo(UserStatus.PENDING);
        googleLogin("HORSE_OWNER").andExpect(status().isBadRequest());
        manager.approve(null);
        users.save(manager);
        googleLogin(role).andExpect(status().isOk());
    }

    @Test
    void incorrectPasswordDoesNotActivateOldPendingUser() throws Exception {
        seed("HORSE_OWNER", "user@example.com");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"incorrect\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findByEmailIgnoreCase("user@example.com").orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);
    }


    @Test
    void horseOwnerCannotApproveOtherUsers() throws Exception {
        register("HORSE_OWNER").andExpect(status().isCreated());
        var auth = login().andExpect(status().isOk()).andReturn();
        String token = json.readTree(auth.getResponse().getContentAsString()).get("accessToken").asText();
        AppUser pending = seed("HEAD_TRAINER", "trainer@example.com");
        mockMvc.perform(post("/api/auth/users/" + pending.getId() + "/approve")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(users.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER"})
    void pendingStaffCannotRefreshOrBeAutoActivated(String role) throws Exception {
        AppUser user = seed(role, "user@example.com");
        tokens.save(new com.example.springbootbackend.auth.entity.RefreshToken(
                user, "pending-refresh", java.time.LocalDateTime.now().plusDays(1)));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", "pending-refresh"))))
                .andExpect(status().isBadRequest());
        login().andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);
    }


    @Test
    void accessTokenCannotBypassAccountLock() throws Exception {
        register("HORSE_OWNER");
        String token = json.readTree(login().andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roleName").value("HORSE_OWNER"));
        AppUser user = users.findByEmailIgnoreCase("user@example.com").orElseThrow();
        user.lock(); users.save(user);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me")
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenAndAnonymousCannotReadCurrentUser() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me")
                .header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyGooglePlaceholderCannotBeUsedAsPassword() throws Exception {
        AppUser user = new AppUser("Google User", "user@example.com", null, encoder.encode("{google-login}"),
                roles.findByName("HORSE_OWNER").orElseThrow());
        user.approve(null); users.save(user);
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", "user@example.com", "password", "{google-login}"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshTokenCannotBeReusedAfterRotationOrLogout() throws Exception {
        register("HORSE_OWNER");
        String token = json.readTree(login().andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
        String body = json.writeValueAsString(java.util.Map.of("refreshToken", token));
        var response = mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        String next = json.readTree(response.getResponse().getContentAsString()).get("refreshToken").asText();
        String nextBody = json.writeValueAsString(java.util.Map.of("refreshToken", next));
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(nextBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(nextBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void normalizedEmailCannotRegisterTwice() throws Exception {
        register("HORSE_OWNER");
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("fullName", "User", "email", " USER@example.com ",
                        "password", "password123", "roleName", "HORSE_OWNER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Email already exists"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", " USER@example.com ", "password", "password123"))))
                .andExpect(status().isOk());
    }

    @Test
    void longPasswordAndInvalidEmailReturnUsefulErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("fullName", "User", "email", "user@example.com",
                        "password", "p".repeat(73), "roleName", "HORSE_OWNER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", "invalid", "password", "password123"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }

    private AppUser seed(String role, String email) {
        return users.save(new AppUser("Test User", email, null, encoder.encode("password123"),
                roles.findByName(role).orElseThrow()));
    }

    private ResultActions register(String role) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("fullName", "Test User", "email", "user@example.com",
                        "password", "password123", "roleName", role))));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"));
    }

    private ResultActions googleLogin(String role) throws Exception {
        return mockMvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("idToken", "valid-token", "roleName", role))));
    }
}
