package com.example.springbootbackend.headtrainer;

import com.example.springbootbackend.veterinarian.support.ApiPage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('HEAD_TRAINER')")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Head Trainer")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HeadTrainerController {
    private final HeadTrainerService service;
    private final RaceSimulationService simulations;

    public HeadTrainerController(HeadTrainerService service, RaceSimulationService simulations) {
        this.service = service;
        this.simulations = simulations;
    }

    @GetMapping("/head-trainer/overview")
    public Map<String, Object> overview(Principal user,
            @RequestParam(defaultValue = "false") boolean include_simulated) {
        return service.overview(user.getName(), include_simulated);
    }

    @GetMapping("/head-trainer/stable-incidents")
    public Map<String,Object> stableIncidents(Principal user) {
        return service.stableIncidents(user.getName());
    }

    @PatchMapping("/head-trainer/stable-incidents/{id}/result")
    public Map<String,Object> submitIncidentResult(Principal user,HttpServletRequest request,
            @PathVariable UUID id,@RequestBody JsonNode body) {
        return service.submitIncidentResult(user.getName(),request.getRemoteAddr(),id,body);
    }

    @GetMapping("/horses/{horseId}/training-metrics")
    public List<Map<String, Object>> metrics(Principal user, @PathVariable UUID horseId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "false") boolean include_simulated) {
        return service.metrics(user.getName(), horseId, from, to, include_simulated);
    }

    @GetMapping("/head-trainer/training-metrics/compare")
    public Map<String, Object> compareMetrics(Principal user, @RequestParam String horse_ids,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "false") boolean include_simulated) {
        return service.compareMetrics(user.getName(), horse_ids, from, to, include_simulated);
    }

    @PostMapping("/training-schedules/{scheduleId}/metrics")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> recordMetrics(Principal user, HttpServletRequest request,
            @PathVariable UUID scheduleId, @RequestBody JsonNode body) {
        return service.recordMetrics(user.getName(), request.getRemoteAddr(), scheduleId, body);
    }

    @PostMapping("/horses/{horseId}/training-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPlan(Principal user, HttpServletRequest request,
            @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.createPlan(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    @GetMapping("/horses/{horseId}/training-plans")
    public Map<String, Object> plans(Principal user, @PathVariable UUID horseId,
            @RequestParam(defaultValue = "false") boolean active_only) {
        return service.plans(user.getName(), horseId, active_only);
    }

    @PatchMapping("/training-plans/{id}")
    public Map<String, Object> updatePlan(Principal user, HttpServletRequest request,
            @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.updatePlan(user.getName(), request.getRemoteAddr(), id, body);
    }

    @DeleteMapping("/training-plans/{id}")
    public Map<String, Object> deletePlan(Principal user, HttpServletRequest request, @PathVariable UUID id) {
        return service.deletePlan(user.getName(), request.getRemoteAddr(), id);
    }

    @PostMapping("/horses/{horseId}/training-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSchedule(Principal user, HttpServletRequest request,
            @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.createSchedule(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    @GetMapping("/head-trainer/calendar")
    public Map<String, Object> calendar(Principal user, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID horse_id,
            @RequestParam(required = false) Long groom_id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        return service.calendar(user.getName(), from, to, horse_id, groom_id, new ApiPage(page, limit));
    }

    @GetMapping("/head-trainer/grooms")
    public Map<String, Object> grooms(Principal user) { return service.grooms(user.getName()); }

    @PatchMapping("/training-schedules/{id}/assign-groom")
    public Map<String, Object> assignGroom(Principal user, HttpServletRequest request,
            @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.assignGroom(user.getName(), request.getRemoteAddr(), id, body);
    }

    @GetMapping("/training-schedules/{id}")
    public Map<String, Object> schedule(Principal user, @PathVariable UUID id) {
        return service.schedule(user.getName(), id);
    }

    @PatchMapping("/training-schedules/{id}")
    public Map<String, Object> updateSchedule(Principal user, HttpServletRequest request,
            @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.updateSchedule(user.getName(), request.getRemoteAddr(), id, body);
    }

    @PostMapping("/horses/{horseId}/race-entries")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerRace(Principal user, HttpServletRequest request,
            @PathVariable UUID horseId, @RequestBody JsonNode body) {
        return service.registerRace(user.getName(), request.getRemoteAddr(), horseId, body);
    }

    @PatchMapping("/race-entries/{entryId}/result")
    public Map<String,Object> recordRaceResult(Principal user,HttpServletRequest request,@PathVariable UUID entryId,
            @RequestBody JsonNode body) {
        return service.recordRaceResult(user.getName(),request.getRemoteAddr(),entryId,body);
    }

    @PostMapping("/training-schedules/{id}/videos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addTrainingVideo(Principal user,HttpServletRequest request,@PathVariable UUID id,
            @RequestBody JsonNode body) {
        return service.addTrainingVideo(user.getName(),request.getRemoteAddr(),id,body);
    }

    @GetMapping("/horses/{horseId}/race-entries")
    public Map<String, Object> raceEntries(Principal user, @PathVariable UUID horseId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int limit) {
        return service.raceEntries(user.getName(), horseId, new ApiPage(page, limit));
    }

    @GetMapping("/head-trainer/races")
    public Map<String, Object> races(Principal user, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        return service.races(user.getName(), from, to, new ApiPage(page, limit));
    }

    @PostMapping("/head-trainer/race-simulations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSimulation(Principal user, HttpServletRequest request,
            @RequestBody JsonNode body) {
        return simulations.create(user.getName(), request.getRemoteAddr(), body);
    }

    @GetMapping("/head-trainer/race-simulations/{id}")
    public Map<String, Object> simulation(Principal user, @PathVariable UUID id) {
        return simulations.detail(user.getName(), id);
    }

    @PostMapping("/head-trainer/race-simulations/{id}/finish")
    public Map<String, Object> finishSimulation(Principal user, HttpServletRequest request,
            @PathVariable UUID id, @RequestBody JsonNode body) {
        return simulations.finish(user.getName(), request.getRemoteAddr(), id, body);
    }
}
