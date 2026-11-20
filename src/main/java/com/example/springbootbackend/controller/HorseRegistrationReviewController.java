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
    public List<Map<String, Object>> list(Principal principal,
            @RequestParam(name = "status", required = false) String status) {
        return service.managerRequests(principal.getName(), status);
    }

    @GetMapping("/pending-count")
    public Map<String, Object> pendingCount(Principal principal) {
        return service.pendingRequestCount(principal.getName());
    }

    @GetMapping("/{requestId}/documents/{docId}/url")
    public Map<String, Object> documentUrl(Principal principal,
            @PathVariable("requestId") String requestId,
            @PathVariable("docId") String docId,
            @RequestParam(name = "download", defaultValue = "false") boolean download) {
        return service.freshDocumentUrlForManager(principal.getName(), requestId, docId, download);
    }

    @PatchMapping("/{requestId}")
    public Map<String, Object> review(Principal principal, HttpServletRequest request,
            @PathVariable("requestId") String requestId, @RequestBody JsonNode body) {
        return service.review(principal.getName(), requestId, body, request.getRemoteAddr());
    }
}
