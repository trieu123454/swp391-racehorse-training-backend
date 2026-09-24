package com.example.springbootbackend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(max = 100)
        String fullName,

        @NotBlank
        @Email
        @Size(max = 100)
        String email,

        @Size(max = 20)
        String phone,

        @NotBlank
        @Size(min = 8, max = 100)
        String password,

        @NotBlank
        String roleName
) {
}
