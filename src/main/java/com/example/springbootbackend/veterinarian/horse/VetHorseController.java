package com.example.springbootbackend.veterinarian.horse;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
@io.swagger.v3.oas.annotations.tags.Tag(name="Veterinarian - Horse health and training lock")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class VetHorseController {
    private final VetHorseService service;
    public VetHorseController(VetHorseService service) { this.service=service; }
    @GetMapping("/vet/health-overview")
    public Map<String,Object> overview(Principal p,@RequestParam(required=false) String section) { return service.overview(p.getName(),section); }
    @PatchMapping("/horses/{id}/health-status")
    public Map<String,Object> status(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) { return service.status(p.getName(),r.getRemoteAddr(),id.toString(),body); }
    @PutMapping("/horses/{id}/training-lock")
    public Map<String,Object> lock(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) { return service.lock(p.getName(),r.getRemoteAddr(),id.toString(),body); }
    @PostMapping("/horses/{id}/training-unlock")
    public Map<String,Object> unlock(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) { return service.unlock(p.getName(),r.getRemoteAddr(),id.toString(),body); }
}
