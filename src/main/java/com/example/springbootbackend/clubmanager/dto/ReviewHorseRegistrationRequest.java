package com.example.springbootbackend.clubmanager.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record ReviewHorseRegistrationRequest(
    @NotNull Action action,
    @JsonAlias("stable_box_id") @Schema(description="Required to approve REGISTER; optional for LINK if the horse already has a stable") UUID stableBoxId,
    @JsonAlias("horse_id") @Schema(description="Required to approve LINK; omit for REGISTER") UUID horseId,
    @JsonAlias("review_note") @Size(max=2000) @Schema(description="Required for REQUEST_INFORMATION and REJECT") String reviewNote) {
    public enum Action { APPROVE, REQUEST_INFORMATION, REJECT }
}
