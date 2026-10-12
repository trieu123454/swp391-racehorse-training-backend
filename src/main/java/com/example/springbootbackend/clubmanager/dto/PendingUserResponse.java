package com.example.springbootbackend.clubmanager.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PendingUserResponse(
        Long id,
        @JsonProperty("full_name") String fullName,
        String email,
        String phone,
        @JsonProperty("role_name") String roleName,
        String status,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
