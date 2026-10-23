package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @NotBlank
        String idToken,

        String roleName
) {
}
