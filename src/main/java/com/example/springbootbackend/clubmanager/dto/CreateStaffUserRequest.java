package com.example.springbootbackend.clubmanager.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStaffUserRequest(
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Email @Size(max = 100) String email,
        @Size(max = 20) String phone,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank String roleName
) {
    public CreateStaffUserRequest {
        if (fullName != null) fullName = fullName.trim();
        if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (roleName != null) roleName = roleName.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
