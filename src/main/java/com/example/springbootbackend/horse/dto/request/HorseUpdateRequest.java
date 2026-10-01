package com.example.springbootbackend.horse.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.util.UUID;

public record HorseUpdateRequest(
        @JsonAlias("horse_name") @NotBlank @Size(min = 2, max = 100) String horseName,
        @Size(max = 50) String breed,
        @JsonAlias("birth_year") @Min(1900) Integer birthYear,
        @JsonAlias("height_cm") @DecimalMin("50.0") @DecimalMax("250.0") @Digits(integer = 3, fraction = 1) BigDecimal heightCm,
        @JsonAlias("pedigree_father") @Size(max = 100) String pedigreeFather,
        @JsonAlias("pedigree_mother") @Size(max = 100) String pedigreeMother,
        @JsonAlias("image_url") @Size(max = 512) String imagePath,
        @JsonAlias("stable_box_id") @NotNull UUID stableBoxId,
        @JsonAlias("owner_id") @Positive Long ownerId,
        boolean confirmOwnerChange) {
}
