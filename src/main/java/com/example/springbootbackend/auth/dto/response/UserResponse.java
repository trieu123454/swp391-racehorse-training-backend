package com.example.springbootbackend.auth.dto.response;

import java.time.LocalDateTime;

import com.example.springbootbackend.auth.entity.AppUser;
import com.example.springbootbackend.auth.entity.UserStatus;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        String roleName,
        UserStatus status,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().getName(),
                user.getStatus(),
                user.getApprovedAt(),
                user.getCreatedAt()
        );
    }
}
