package com.example.springbootbackend.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PendingUserResponse(
        Long id,
        @JsonProperty("full_name") String fullName,
        String email,
        String phone,
        @JsonProperty("role_name") String roleName,
        String status,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
