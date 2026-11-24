package com.example.springbootbackend.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record OwnershipTransferRequest(
    @JsonAlias("horse_id") @NotNull UUID horseId,
    @JsonAlias("new_owner_email") @NotBlank @Email @Size(max = 255) String newOwnerEmail,
    @JsonAlias("notes") @Size(max = 2000) String reason) {}
