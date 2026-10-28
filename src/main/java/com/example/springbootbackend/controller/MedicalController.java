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
import com.example.springbootbackend.service.MedicalService;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('VETERINARIAN')")
@Tag(name = "Veterinarian - Medical records, prescriptions, diets and injuries")
@SecurityRequirement(name = "bearerAuth")
public class MedicalController {
    private final MedicalService service;

    public MedicalController(MedicalService service) {
        this.service = service;
    }

    @PostMapping("/horses/{horseId}/medical-records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRecord(Principal p, HttpServletRequest r, @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.createRecord(p.getName(), r.getRemoteAddr(), horseId.toString(), body);
    }

    @GetMapping("/horses/{horseId}/medical-records")
    public Map<String, Object> records(
            Principal p,
            @PathVariable UUID horseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.listRecords(p.getName(), horseId.toString(), new ApiPage(page, limit));
    }

    @GetMapping("/medical-records/{id}")
    public Map<String, Object> record(Principal p, @PathVariable UUID id) {
        return service.record(p.getName(), id.toString());
    }

    @PatchMapping("/medical-records/{id}")
    public Map<String, Object> updateRecord(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.updateRecord(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }

    @PostMapping("/medical-records/{id}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> prescribe(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.createPrescription(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }

    @PatchMapping("/prescriptions/{id}")
    public Map<String, Object> prescription(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.updatePrescription(p.getName(), r.getRemoteAddr(), id.toString(), body);
    }

    @GetMapping("/horses/{horseId}/prescriptions")
    public Map<String, Object> prescriptions(
            Principal p,
            @PathVariable UUID horseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.prescriptions(p.getName(), horseId.toString(), status, new ApiPage(page, limit));
    }

    @PostMapping("/horses/{horseId}/diet-records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> diet(Principal p, HttpServletRequest r, @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.saveDiet(p.getName(), r.getRemoteAddr(), horseId.toString(), null, body);
    }

    @PatchMapping("/diet-records/{id}")
    public Map<String, Object> updateDiet(Principal p, HttpServletRequest r, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.saveDiet(p.getName(), r.getRemoteAddr(), null, id.toString(), body);
    }

    @GetMapping("/horses/{horseId}/diet-records")
    public Map<String, Object> diets(
            Principal p,
            @PathVariable UUID horseId,
            @RequestParam(name = "active_on", required = false) LocalDate activeOn,
            @RequestParam(name = "include_history", defaultValue = "false") boolean history,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.diets(p.getName(), horseId.toString(), activeOn, history, new ApiPage(page, limit));
    }

    @PostMapping("/horses/{horseId}/injury-markers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> injury(Principal p, HttpServletRequest r, @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.createInjury(p.getName(), r.getRemoteAddr(), horseId.toString(), body);
    }

    @GetMapping("/horses/{horseId}/injury-markers")
    public Map<String, Object> injuries(
            Principal p,
            @PathVariable UUID horseId,
            @RequestParam(name = "latest_only", defaultValue = "false") boolean latest,
            @RequestParam(name = "body_part", required = false) String bodyPart,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return service.injuries(p.getName(), horseId.toString(), latest, bodyPart, new ApiPage(page, limit));
    }
}
