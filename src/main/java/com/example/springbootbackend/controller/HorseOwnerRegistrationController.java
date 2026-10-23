package com.example.springbootbackend.controller;

import com.example.springbootbackend.service.HorseRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/horse-owner/horse-registration-requests")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseOwnerRegistrationController {
    private final HorseRegistrationService service;

    public HorseOwnerRegistrationController(HorseRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> requests(Principal principal) {
        return service.ownerRequests(principal.getName());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(Principal principal,
            @RequestPart("request") JsonNode body,
            @RequestPart("horseImage") MultipartFile horseImage,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents) {
        return service.submit(principal.getName(), body, horseImage, documents);
    }
}
