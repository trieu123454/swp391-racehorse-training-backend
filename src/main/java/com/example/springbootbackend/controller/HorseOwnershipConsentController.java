package com.example.springbootbackend.controller;

import com.example.springbootbackend.service.HorseRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/horse-owner/ownership-transfer-requests")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseOwnershipConsentController {
    private final HorseRegistrationService service;

    public HorseOwnershipConsentController(HorseRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> pending(Principal principal) {
        return service.transferRequests(principal.getName());
    }

    @PatchMapping("/{requestId}/consent")
    public Map<String, Object> respond(Principal principal, HttpServletRequest request,
            @PathVariable String requestId, @RequestBody JsonNode body) {
        return service.respondToTransfer(principal.getName(), requestId, body, request.getRemoteAddr());
    }
}
