package com.example.springbootbackend.controller;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.springbootbackend.dto.request.ApiPage;
import com.example.springbootbackend.service.HealthExamService;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('VETERINARIAN')")
@Tag(name = "Veterinarian - Health exams")
@SecurityRequirement(name = "bearerAuth")
public class HealthExamController {
    private final HealthExamService service;

    public HealthExamController(HealthExamService service) {
        this.service = service;
    }

    @PostMapping("/horses/{horseId}/health-exams")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Principal user, HttpServletRequest request, @PathVariable UUID horseId,
                                      @RequestBody JsonNode body) {
        return service.create(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    @GetMapping("/horses/{horseId}/health-exams")
    public Map<String, Object> list(
            Principal user,
            @PathVariable UUID horseId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.list(user.getName(), horseId, from, to, new ApiPage(page, limit));
    }

    @GetMapping("/health-exams/{id}")
    public Map<String, Object> detail(Principal user, @PathVariable UUID id) {
        return service.detail(user.getName(), id);
    }

    @GetMapping("/health-exams/{id}/logs")
    public Map<String, Object> logs(
            Principal user,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.logs(user.getName(), id, new ApiPage(page, limit));
    }

    @PatchMapping("/health-exams/{id}")
    public Map<String, Object> update(Principal user, HttpServletRequest request, @PathVariable UUID id,
                                      @RequestBody JsonNode body) {
        return service.update(user.getName(), request.getRemoteAddr(), id, body);
    }
}
