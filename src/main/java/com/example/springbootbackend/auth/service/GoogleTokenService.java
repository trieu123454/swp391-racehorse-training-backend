package com.example.springbootbackend.auth.service;

import java.util.Map;

import com.example.springbootbackend.auth.exception.AuthException;
import com.example.springbootbackend.security.GoogleProperties;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GoogleTokenService {

    private final GoogleProperties googleProperties;

    public GoogleTokenService(GoogleProperties googleProperties) {
        this.googleProperties = googleProperties;
    }

    public GoogleProfile verify(String idToken) {
        try {
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
            Jwt jwt = decoder.decode(idToken);

            String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
                throw new AuthException("Google token issuer is invalid");
            }

            String configuredClientId = googleProperties.clientId();
            if (StringUtils.hasText(configuredClientId) && !jwt.getAudience().contains(configuredClientId)) {
                throw new AuthException("Google token audience is invalid");
            }

            Map<String, Object> claims = jwt.getClaims();
            Boolean emailVerified = (Boolean) claims.getOrDefault("email_verified", Boolean.FALSE);
            if (!emailVerified) {
                throw new AuthException("Google email is not verified");
            }

            return new GoogleProfile(
                    claims.get("email").toString(),
                    claims.getOrDefault("name", claims.get("email")).toString()
            );
        } catch (JwtException ex) {
            throw new AuthException("Google token is invalid");
        }
    }

    public record GoogleProfile(String email, String fullName) {
    }
}
