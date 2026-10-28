package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RoleChangeActionRequest(@NotBlank(message = "Action không được để trống") String action) {
}
