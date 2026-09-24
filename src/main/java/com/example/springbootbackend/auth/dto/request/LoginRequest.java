package com.example.springbootbackend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank
        @Email
        String email,

        @NotBlank
        String password
) {
    public LoginRequest {
        if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
