package com.example.springbootbackend.controller;

import com.example.springbootbackend.service.HorseRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/horse-owner/ownership-transfer-requests")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class HorseOwnershipConsentController {
  private final HorseRegistrationService service;
  private final com.example.springbootbackend.service.HorseOwnerRequestService outgoing;

  public HorseOwnershipConsentController(
      HorseRegistrationService service,
      com.example.springbootbackend.service.HorseOwnerRequestService outgoing) {
    this.service = service;
    this.outgoing = outgoing;
  }

  @GetMapping
  public List<Map<String, Object>> pending(Principal principal) {
    var result = new java.util.ArrayList<>(service.transferRequests(principal.getName()));
    var submissions = outgoing.transfers(principal.getName());
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) submissions.get("items");
    result.addAll(items);
    return result;
  }

  @PatchMapping("/{requestId}/consent")
  public Map<String, Object> respond(
      Principal principal,
      HttpServletRequest request,
      @PathVariable("requestId") String requestId,
      @RequestBody JsonNode body) {
    return service.respondToTransfer(principal.getName(), requestId, body, request.getRemoteAddr());
  }
}
