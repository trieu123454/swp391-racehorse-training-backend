package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4, max = 10) String code,
        @NotBlank @Size(min = 8, max = 100) String newPassword
) {
    public ResetPasswordRequest {
        if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (code != null) code = code.trim();
    }
}

