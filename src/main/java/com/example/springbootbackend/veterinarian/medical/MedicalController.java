package com.example.springbootbackend.veterinarian.medical;

import com.example.springbootbackend.veterinarian.support.ApiPage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('VETERINARIAN')")
@io.swagger.v3.oas.annotations.tags.Tag(name="Veterinarian - Medical records, prescriptions, diets and injuries")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class MedicalController {
    private final MedicalService service;
    public MedicalController(MedicalService service) { this.service=service; }
    @PostMapping("/horses/{horseId}/medical-records") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createRecord(Principal p,HttpServletRequest r,@PathVariable UUID horseId,@RequestBody JsonNode body) {
        return service.createRecord(p.getName(),r.getRemoteAddr(),horseId.toString(),body);
    }
    @GetMapping("/horses/{horseId}/medical-records")
    public Map<String,Object> records(Principal p,@PathVariable UUID horseId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.listRecords(p.getName(),horseId.toString(),new ApiPage(page,limit));
    }
    @GetMapping("/medical-records/{id}")
    public Map<String,Object> record(Principal p,@PathVariable UUID id) { return service.record(p.getName(),id.toString()); }
    @PatchMapping("/medical-records/{id}")
    public Map<String,Object> updateRecord(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.updateRecord(p.getName(),r.getRemoteAddr(),id.toString(),body);
    }
    @PostMapping("/medical-records/{id}/prescriptions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> prescribe(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.createPrescription(p.getName(),r.getRemoteAddr(),id.toString(),body);
    }
    @PatchMapping("/prescriptions/{id}")
    public Map<String,Object> prescription(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.updatePrescription(p.getName(),r.getRemoteAddr(),id.toString(),body);
    }
    @GetMapping("/horses/{horseId}/prescriptions")
    public Map<String,Object> prescriptions(Principal p,@PathVariable UUID horseId,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.prescriptions(p.getName(),horseId.toString(),status,new ApiPage(page,limit));
    }
    @PostMapping("/horses/{horseId}/diet-records") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> diet(Principal p,HttpServletRequest r,@PathVariable UUID horseId,@RequestBody JsonNode body) {
        return service.saveDiet(p.getName(),r.getRemoteAddr(),horseId.toString(),null,body);
    }
    @PatchMapping("/diet-records/{id}")
    public Map<String,Object> updateDiet(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.saveDiet(p.getName(),r.getRemoteAddr(),null,id.toString(),body);
    }
    @GetMapping("/horses/{horseId}/diet-records")
    public Map<String,Object> diets(Principal p,@PathVariable UUID horseId,
            @RequestParam(name="active_on",required=false) LocalDate activeOn,@RequestParam(name="include_history",defaultValue="false") boolean history,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.diets(p.getName(),horseId.toString(),activeOn,history,new ApiPage(page,limit));
    }
    @PostMapping("/horses/{horseId}/injury-markers") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> injury(Principal p,HttpServletRequest r,@PathVariable UUID horseId,@RequestBody JsonNode body) {
        return service.createInjury(p.getName(),r.getRemoteAddr(),horseId.toString(),body);
    }
    @GetMapping("/horses/{horseId}/injury-markers")
    public Map<String,Object> injuries(Principal p,@PathVariable UUID horseId,
            @RequestParam(name="latest_only",defaultValue="false") boolean latest,@RequestParam(name="body_part",required=false) String bodyPart,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.injuries(p.getName(),horseId.toString(),latest,bodyPart,new ApiPage(page,limit));
    }
}
