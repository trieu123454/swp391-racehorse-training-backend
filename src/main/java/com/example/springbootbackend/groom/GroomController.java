package com.example.springbootbackend.groom;

import com.example.springbootbackend.horse.storage.HorseStorage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/groom")
@PreAuthorize("hasRole('GROOM')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class GroomController {
    private final GroomWorkspaceService service;
    private final GroomHorseService horses;
    private final HorseStorage storage;

    public GroomController(GroomWorkspaceService service, GroomHorseService horses,HorseStorage storage) {
        this.service = service;
        this.horses = horses;
        this.storage = storage;
    }

    @GetMapping("/my-horses")
    public Map<String, Object> myHorses(Principal user) {
        return horses.myHorses(user.getName());
    }

    @GetMapping("/calendar")
    public Map<String, Object> calendar(Principal user, @RequestParam(required = false) LocalDate date) {
        return horses.calendar(user.getName(), date);
    }

    @GetMapping("/horses/{horseId}")
    public Map<String, Object> horse(Principal user, @PathVariable UUID horseId) {
        return horses.horse(user.getName(), horseId);
    }

    @GetMapping("/horses/{horseId}/diet-records")
    public Map<String, Object> dietRecords(Principal user, @PathVariable UUID horseId,
            @RequestParam(name = "active_on", required = false) LocalDate activeOn) {
        return Map.of("data", horses.dietRecords(user.getName(), horseId, activeOn));
    }

    @PatchMapping({"/daily-tasks/{id}/complete", "/tasks/{id}/complete"})
    public Map<String, Object> completeTask(Principal user, HttpServletRequest request, @PathVariable UUID id) {
        return service.completeTask(user.getName(), request.getRemoteAddr(), id);
    }

    @PatchMapping("/training-schedules/{id}/complete")
    public Map<String, Object> completeTrainingSession(Principal user, HttpServletRequest request,
            @PathVariable UUID id) {
        return service.completeTrainingSession(user.getName(), request.getRemoteAddr(), id);
    }

    @PostMapping("/horses/{horseId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reportIncident(Principal user, HttpServletRequest request,
            @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.reportIncident(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    /** Legacy path retained for existing clients; it has identical scope and validation. */
    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reportIncidentLegacy(Principal user, HttpServletRequest request,
            @RequestBody JsonNode body) {
        return service.reportIncident(user.getName(), request.getRemoteAddr(), body);
    }

    @PostMapping(value="/incidents/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> uploadIncidentImage(Principal user,@RequestParam MultipartFile file) {
        long groomId=service.requireGroom(user.getName());
        return storage.uploadIncident(file,groomId);
    }

    @GetMapping("/incidents")
    public Map<String, Object> incidents(Principal user,
            @RequestParam(required = false) String status,
            @RequestParam(name = "horse_id", required = false) UUID horseId) {
        return service.incidents(user.getName(), status, horseId);
    }

    @PatchMapping("/incidents/{id}/result")
    public Map<String, Object> submitIncidentResult(Principal user, HttpServletRequest request,
            @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.submitIncidentResult(user.getName(), request.getRemoteAddr(), id, body);
    }

    @GetMapping("/inventory")
    public Map<String, Object> inventory(Principal user,
            @RequestParam(required = false) String category,
            @RequestParam(name = "low_stock_only", defaultValue = "false") boolean lowStockOnly) {
        return service.inventory(user.getName(), category, lowStockOnly);
    }

    @PostMapping("/supply-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSupplyRequest(Principal user, HttpServletRequest request,
            @RequestBody JsonNode body) {
        return service.createSupplyRequest(user.getName(), request.getRemoteAddr(), body);
    }

    @GetMapping("/supply-requests")
    public Map<String, Object> supplyRequests(Principal user, @RequestParam(required = false) String status) {
        return service.supplyRequests(user.getName(), status);
    }

    /** Sanitized compatibility view for the earlier workspace client. */
    @GetMapping("/workspace")
    public Map<String, Object> workspace(Principal user, @RequestParam(required = false) LocalDate date) {
        return service.workspace(user.getName(), date);
    }
}
