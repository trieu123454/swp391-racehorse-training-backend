package com.example.springbootbackend.controller;

import com.example.springbootbackend.service.HorseRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/club-manager/horse-registration-requests")
@PreAuthorize("hasRole('CLUB_MANAGER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseRegistrationReviewController {
    private final HorseRegistrationService service;

    public HorseRegistrationReviewController(HorseRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> pending(Principal principal) {
        return service.pendingRequests(principal.getName());
    }

    @PatchMapping("/{requestId}")
    public Map<String, Object> review(Principal principal, HttpServletRequest request,
            @PathVariable String requestId, @RequestBody JsonNode body) {
        return service.review(principal.getName(), requestId, body, request.getRemoteAddr());
    }
}
