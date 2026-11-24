package com.example.springbootbackend.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record HorseRegistrationRequest(
    @JsonAlias("horse_name") @Size(min = 2, max = 100) String horseName,
    @Size(max = 50) String breed,
    @JsonAlias("birth_year") @Min(1900) Integer birthYear,
    @JsonAlias("pedigree_father") @Size(max = 100) String pedigreeFather,
    @JsonAlias("pedigree_mother") @Size(max = 100) String pedigreeMother,
    @JsonAlias("height_cm")
        @DecimalMin("50.0")
        @DecimalMax("250.0")
        @Digits(integer = 3, fraction = 1)
        BigDecimal heightCm,
    @JsonAlias("current_weight_kg")
        @DecimalMin("100.0")
        @DecimalMax("900.0")
        @Digits(integer = 3, fraction = 2)
        BigDecimal currentWeightKg,
    @JsonAlias({"notes", "ownerNote", "owner_note"}) @Size(max = 2000) String reason,
    @JsonAlias("request_type") @Pattern(regexp = "REGISTER|LINK") String requestType,
    @JsonAlias("microchip_number") @Size(max = 100) String microchipNumber,
    @JsonAlias("passport_number") @Size(max = 100) String passportNumber) {}
