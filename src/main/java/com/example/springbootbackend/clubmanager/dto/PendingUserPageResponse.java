package com.example.springbootbackend.clubmanager.dto;

import java.util.List;

public record PendingUserPageResponse(
        List<PendingUserResponse> data,
        long total,
        int page,
        int limit) {
}
