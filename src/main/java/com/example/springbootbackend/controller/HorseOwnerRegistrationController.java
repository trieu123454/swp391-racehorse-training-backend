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
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "multipart/form-data",
              encoding = {
                @io.swagger.v3.oas.annotations.media.Encoding(
                    name = "request",
                    contentType = "application/json"),
                @io.swagger.v3.oas.annotations.media.Encoding(
                    name = "documentTypes",
                    contentType = "application/json")
              }))
  public Map<String, Object> submit(
      Principal principal,
      @RequestPart("request") JsonNode body,
      @RequestPart(value = "horseImage", required = false) MultipartFile horseImage,
      @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
      @RequestPart(value = "documentTypes", required = false) JsonNode documentTypes,
      HttpServletRequest request) {
    return service.submit(
        principal.getName(), body, horseImage, documents, documentTypes, request.getRemoteAddr());
  }

  @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "multipart/form-data",
              encoding = {
                @io.swagger.v3.oas.annotations.media.Encoding(
                    name = "request",
                    contentType = "application/json"),
                @io.swagger.v3.oas.annotations.media.Encoding(
                    name = "documentTypes",
                    contentType = "application/json")
              }))
  public Map<String, Object> resubmit(
      Principal principal,
      HttpServletRequest request,
      @PathVariable("id") String requestId,
      @RequestPart(value = "request", required = false) JsonNode body,
      @RequestPart(value = "horseImage", required = false) MultipartFile horseImage,
      @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
      @RequestPart(value = "documentTypes", required = false) JsonNode documentTypes) {
    return service.resubmit(
        principal.getName(),
        requestId,
        body,
        horseImage,
        documents,
        documentTypes,
        request.getRemoteAddr());
  }

  @GetMapping("/{id}/documents/{docId}/url")
  public Map<String, Object> documentUrl(
      Principal principal,
      @PathVariable("id") String requestId,
      @PathVariable("docId") String docId,
      @RequestParam(name = "download", defaultValue = "false") boolean download) {
    return service.freshDocumentUrlForOwner(principal.getName(), requestId, docId, download);
  }
}
