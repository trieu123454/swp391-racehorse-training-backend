package com.example.springbootbackend.controller;

import com.example.springbootbackend.dto.request.HorseRegistrationRequest;
import com.example.springbootbackend.dto.request.OwnershipTransferRequest;
import com.example.springbootbackend.service.HorseOwnerRequestService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** JSON compatibility routes; multipart and owner-consent routes use the modern controllers. */
@RestController
@RequestMapping("/api/horse-owner")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseOwnerRequestController {
  private final HorseOwnerRequestService service;

  public HorseOwnerRequestController(HorseOwnerRequestService service) {
    this.service = service;
  }

  @PostMapping(value = "/horse-registration-requests", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> register(
      Principal p, @Valid @RequestBody HorseRegistrationRequest input) {
    return service.register(p.getName(), input);
  }

  @GetMapping("/horse-registration-requests/{id}")
  public Map<String, Object> registration(Principal p, @PathVariable("id") String id) {
    return service.registration(p.getName(), id);
  }

  @PatchMapping(value = "/horse-registration-requests/{id}", consumes = "application/json")
  public Map<String, Object> supplement(
      Principal p,
      @PathVariable("id") String id,
      @Valid @RequestBody HorseRegistrationRequest input) {
    return service.save(p.getName(), id, input, null, List.of(), List.of());
  }

  @PostMapping(value = "/ownership-transfer-requests", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> transfer(
      Principal p, @Valid @RequestBody OwnershipTransferRequest input) {
    return service.transfer(p.getName(), input);
  }
}
