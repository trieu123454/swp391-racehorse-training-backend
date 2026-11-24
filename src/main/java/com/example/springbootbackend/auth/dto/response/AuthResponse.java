package com.example.springbootbackend.auth.dto.response;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {
    @Override
    public String toString() {
        return "AuthResponse[accessToken=[REDACTED], refreshToken=[REDACTED], user=" + user + "]";
    }
}
