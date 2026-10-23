package com.example.springbootbackend.controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.springbootbackend.dto.request.ApiPage;
import com.example.springbootbackend.service.NotificationService;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(
            Principal user,
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.list(user.getName(), unreadOnly, new ApiPage(page, limit));
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> read(Principal user, HttpServletRequest request, @PathVariable UUID id) {
        return service.read(user.getName(), request.getRemoteAddr(), id);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(Principal user, HttpServletRequest request) {
        return service.readAll(user.getName(), request.getRemoteAddr());
    }
}
