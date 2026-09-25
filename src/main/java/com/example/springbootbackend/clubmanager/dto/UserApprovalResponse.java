package com.example.springbootbackend.clubmanager.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record UserApprovalResponse(
        Long id,
        String status,
        @JsonProperty("approved_by") Long approvedBy,
        @JsonProperty("approved_at") LocalDateTime approvedAt) {
}
