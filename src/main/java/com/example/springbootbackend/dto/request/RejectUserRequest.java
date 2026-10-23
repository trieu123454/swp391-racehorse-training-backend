package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.Size;

public record RejectUserRequest(@Size(max = 500, message = "Lý do từ chối không được vượt quá 500 ký tự") String reason) {
}
