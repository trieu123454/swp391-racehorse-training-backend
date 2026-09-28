package com.example.springbootbackend.veterinarian.exam;

import com.example.springbootbackend.veterinarian.support.ApiPage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Veterinarian - Health exams")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HealthExamController {
    private final HealthExamService service;
    public HealthExamController(HealthExamService service) { this.service = service; }

    @PostMapping("/horses/{horseId}/health-exams")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Principal user, HttpServletRequest request, @PathVariable UUID horseId,
                                      @RequestBody JsonNode body) {
        return service.create(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    @GetMapping("/horses/{horseId}/health-exams")
    public Map<String, Object> list(Principal user, @PathVariable UUID horseId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.list(user.getName(), horseId, from, to, new ApiPage(page, limit));
    }

    @GetMapping("/health-exams/{id}")
    public Map<String, Object> detail(Principal user, @PathVariable UUID id) { return service.detail(user.getName(), id); }

    @GetMapping("/health-exams/{id}/logs")
    public Map<String, Object> logs(Principal user, @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.logs(user.getName(), id, new ApiPage(page, limit));
    }

    @PatchMapping("/health-exams/{id}")
    public Map<String, Object> update(Principal user, HttpServletRequest request, @PathVariable UUID id,
                                      @RequestBody JsonNode body) {
        return service.update(user.getName(), request.getRemoteAddr(), id, body);
    }
}
