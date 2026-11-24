package com.example.springbootbackend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @NotBlank
        String idToken,

        String roleName
) {
    @Override
    public String toString() {
        return "GoogleLoginRequest[idToken=[REDACTED], roleName=" + roleName + "]";
    }
}
