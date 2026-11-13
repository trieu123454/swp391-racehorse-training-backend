package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @NotBlank @Email String email
) {
    public ForgotPasswordRequest {
        if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

