package com.example.springbootbackend.horseowner.controller;

import com.example.springbootbackend.horseowner.service.HorseOwnerService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/horse-owner")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Horse Owner portal")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseOwnerController {
    private final HorseOwnerService service;

    public HorseOwnerController(HorseOwnerService service) {
        this.service = service;
    }

    @GetMapping("/horses")
    public Map<String, Object> horses(Principal principal) {
        return service.horses(principal.getName());
    }

    @GetMapping("/horses/{horseId}/dashboard")
    public Map<String, Object> dashboard(Principal principal, @PathVariable UUID horseId,
            @RequestParam(required = false) Integer year) {
        return service.dashboard(principal.getName(), horseId, year);
    }
}
