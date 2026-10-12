package com.example.springbootbackend.clubmanager.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserRoleRequest(
        @NotBlank(message = "Vai trò không được để trống") String roleName) {
}
