package com.example.springbootbackend.dto.response;

import java.util.List;

public record PendingUserPageResponse(
        List<PendingUserResponse> data,
        long total,
        int page,
        int limit) {
}
