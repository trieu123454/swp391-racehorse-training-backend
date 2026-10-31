package com.example.springbootbackend.controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.springbootbackend.service.VetHorseService;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('VETERINARIAN')")
@Tag(name = "Veterinarian - Horse health and training lock")
@SecurityRequirement(name = "bearerAuth")
public class VetHorseController {
    private final VetHorseService service;

    public VetHorseController(VetHorseService service) {
        this.service = service;
    }

    @GetMapping("/vet/health-overview")
    public Map<String, Object> overview(Principal p, @RequestParam(required = false) String section) {
        return service.overview(p.getName(), section);
    }

    @GetMapping("/vet/stable-incidents")
    public Map<String, Object> incidents(Principal p, @RequestParam(defaultValue = "Open") String status) {
        return service.incidents(p.getName(), status);
    }

    @PatchMapping("/vet/stable-incidents/{id}/claim")
    public Map<String, Object> claimIncident(Principal p, HttpServletRequest r, @PathVariable UUID id) {
        return service.claimEmergency(p.getName(), r.getRemoteAddr(), id);
    }

    @PatchMapping("/vet/stable-incidents/{id}/result")
    public Map<String, Object> submitIncidentResult(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.submitIncidentResult(p.getName(), r.getRemoteAddr(), id, body);
    }

    @PatchMapping("/vet/stable-incidents/{id}/resolve")
    public Map<String, Object> resolveIncident(Principal p, HttpServletRequest r, @PathVariable UUID id) {
        return service.resolveIncident(p.getName(), r.getRemoteAddr(), id);
    }

    @PatchMapping("/horses/{id}/health-status")
    public Map<String, Object> status(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.status(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }

    @PutMapping("/horses/{id}/training-lock")
    public Map<String, Object> lock(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.lock(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }

    @PostMapping("/horses/{id}/training-unlock")
    public Map<String, Object> unlock(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.unlock(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }
}
