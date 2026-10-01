package com.example.springbootbackend.horse.dto.request;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record HorseRequest(
        @JsonAlias("horse_name") @NotBlank(message="Tên ngựa không được để trống") @Size(min=2,max=100) String horseName,
        @Size(max=50) String breed,
        @JsonAlias("birth_year") @Min(1900) Integer birthYear,
        @JsonAlias("height_cm") @DecimalMin("50.0") @DecimalMax("250.0") @Digits(integer=3,fraction=1) BigDecimal heightCm,
        @JsonProperty("currentWeightKg") @JsonAlias("current_weight_kg") @DecimalMin("100.0") @DecimalMax("900.0") @Digits(integer=3,fraction=2) BigDecimal currentWeightKg,
        @Size(max=100) String pedigreeFather,
        @Size(max=100) String pedigreeMother,
        @JsonAlias("image_url") @Size(max=512) String imagePath,
        @JsonAlias("stable_box_id") @NotNull java.util.UUID stableBoxId,
        @JsonAlias("owner_id") @Positive Long ownerId,
        boolean confirmOwnerChange) {}
