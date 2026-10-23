package com.example.springbootbackend.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserApprovalResponse(
        Long id,
        String status,
        @JsonProperty("approved_by") Long approvedBy,
        @JsonProperty("approved_at") LocalDateTime approvedAt) {
}
