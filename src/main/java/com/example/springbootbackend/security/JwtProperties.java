package com.example.springbootbackend.security;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long accessTokenMinutes,
        long refreshTokenDays
) {
    public JwtProperties {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.jwt.secret must contain at least 32 UTF-8 bytes");
        }
        if (accessTokenMinutes < 1 || refreshTokenDays < 1) {
            throw new IllegalArgumentException("JWT token lifetimes must be positive");
        }
    }
}
