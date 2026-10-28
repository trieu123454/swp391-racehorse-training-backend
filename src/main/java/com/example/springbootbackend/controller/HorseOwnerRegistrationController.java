package com.example.springbootbackend.controller;

import com.example.springbootbackend.service.HorseRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
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
            @RequestPart(value = "horseImage", required = false) MultipartFile horseImage,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
            @RequestPart(value = "documentTypes", required = false) JsonNode documentTypes,
            HttpServletRequest request) {
        return service.submit(principal.getName(), body, horseImage, documents, documentTypes, request.getRemoteAddr());
    }

    @PatchMapping(value = "/{requestId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> resubmit(Principal principal, HttpServletRequest request,
            @PathVariable String requestId,
            @RequestPart("request") JsonNode body,
            @RequestPart(value = "horseImage", required = false) MultipartFile horseImage,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
            @RequestPart(value = "documentTypes", required = false) JsonNode documentTypes) {
        return service.resubmit(principal.getName(), requestId, body, horseImage, documents,
                documentTypes, request.getRemoteAddr());
    }
}
