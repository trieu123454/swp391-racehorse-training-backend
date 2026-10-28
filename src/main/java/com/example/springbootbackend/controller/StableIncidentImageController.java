package com.example.springbootbackend.controller;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.StableIncident;
import com.example.springbootbackend.entity.UserStatus;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.StableIncidentRepository;
import com.example.springbootbackend.service.HorseStorage;

@RestController
@RequestMapping("/api/stable-incidents")
@PreAuthorize("isAuthenticated()")
public class StableIncidentImageController {
    private static final Set<String> IMAGE_ROLES = Set.of("GROOM", "VETERINARIAN", "CLUB_MANAGER");
    private final AppUserRepository users;
    private final StableIncidentRepository incidents;
    private final HorseStorage storage;

    public StableIncidentImageController(
            AppUserRepository users,
            StableIncidentRepository incidents,
            HorseStorage storage) {
        this.users = users;
        this.incidents = incidents;
        this.storage = storage;
    }

    @GetMapping("/{id}/image")
    public Map<String, Object> image(Principal principal, @PathVariable UUID id) {
        AppUser account = users.findByEmailIgnoreCase(principal.getName())
                .filter(u -> u.getStatus() == UserStatus.APPROVED && !u.isMustChangePassword() && u.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Active account required"));
        String role = account.getRole().getName();
        if (!IMAGE_ROLES.contains(role)) throw new ResponseStatusException(FORBIDDEN, "This role cannot view incident photos");
        StableIncident incident = incidents.findById(id.toString())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Incident not found"));
        if ("GROOM".equals(role)) {
            long userId = account.getId();
            boolean reporter = incident.getGroomId() != null && incident.getGroomId() == userId;
            boolean assignee = incident.getAssignedTo() != null && incident.getAssignedTo() == userId;
            if (!reporter && !assignee)
                throw new ResponseStatusException(FORBIDDEN, "Groom can only view photos from their reports or assigned incidents");
        }
        String value = incident.getImageUrl();
        if (value == null || !value.startsWith("incidents/")) return Map.of("hasImage", false);
        return storage.signedUrl(value);
    }
}
