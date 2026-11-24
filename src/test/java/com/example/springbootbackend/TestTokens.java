package com.example.springbootbackend;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.security.JwtService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test JWTs use the same persisted session check as production logins. */
public final class TestTokens {
  private TestTokens() {}

  public static String create(JwtService jwt, JdbcTemplate db, AppUser user) {
    String session = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO refresh_tokens(user_id,session_id,token,expires_at,created_at,revoked) VALUES"
            + " (?,?,?,?,?,FALSE)",
        user.getId(),
        session,
        UUID.randomUUID().toString(),
        java.time.LocalDateTime.now().plusHours(1),
        java.time.LocalDateTime.now());
    return jwt.createAccessToken(user, session);
  }
}
