package com.example.springbootbackend.clubmanager.dto;

import jakarta.validation.constraints.Size;

public record LockUserRequest(@Size(max = 500, message = "Lý do khóa không được vượt quá 500 ký tự") String reason) {
}
