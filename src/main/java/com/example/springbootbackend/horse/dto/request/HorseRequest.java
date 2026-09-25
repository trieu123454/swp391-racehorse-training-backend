package com.example.springbootbackend.horse.dto.request;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAlias;

public record HorseRequest(
        @JsonAlias("horse_name") @NotBlank(message="Tên ngựa không được để trống") @Size(min=2,max=100) String horseName,
        @Size(max=50) String breed,
        @JsonAlias("birth_year") @Min(1900) Integer birthYear,
        @Size(max=100) String pedigreeFather,
        @Size(max=100) String pedigreeMother,
        @JsonAlias("image_url") @Size(max=512) String imagePath,
        @JsonAlias("stable_box_id") @NotNull java.util.UUID stableBoxId,
        @JsonAlias("owner_id") @Positive Long ownerId,
        boolean confirmOwnerChange) {}
