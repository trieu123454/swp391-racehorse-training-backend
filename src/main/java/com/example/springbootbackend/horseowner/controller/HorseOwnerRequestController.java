package com.example.springbootbackend.horseowner.controller;

import com.example.springbootbackend.horseowner.dto.HorseRegistrationRequest;
import com.example.springbootbackend.horseowner.dto.OwnershipTransferRequest;
import com.example.springbootbackend.horseowner.service.HorseOwnerRequestService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Encoding;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/horse-owner")
@PreAuthorize("hasRole('HORSE_OWNER')")
@io.swagger.v3.oas.annotations.tags.Tag(name="Horse Owner profile requests")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class HorseOwnerRequestController {
    private final HorseOwnerRequestService service;
    public HorseOwnerRequestController(HorseOwnerRequestService service) { this.service=service; }

    @GetMapping("/horse-registration-requests")
    public Map<String,Object> registrations(Principal principal) { return service.registrations(principal.getName()); }

    @PostMapping(value="/horse-registration-requests",consumes="application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(Principal principal,@Valid @RequestBody HorseRegistrationRequest input) {
        return service.register(principal.getName(),input);
    }

    @GetMapping("/horse-registration-requests/{id}")
    public Map<String,Object> registration(Principal p,@PathVariable String id) { return service.registration(p.getName(),id); }

    @PostMapping(value="/horse-registration-requests",consumes="multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Register a horse with an optional image and up to five documents",
        description="request and documentTypes are application/json parts. documentTypes is an ordered array of OWNERSHIP_PROOF, HORSE_CERTIFICATE, HEALTH_DOCUMENT, OTHER matching repeated documents parts. Image: JPG/PNG/WebP up to 5 MB. Documents: PDF/JPG/PNG/WebP up to 10 MB each.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(
        encoding={@Encoding(name="request",contentType="application/json"),@Encoding(name="documentTypes",contentType="application/json")}))
    public Map<String,Object> registerMultipart(Principal p,
            @Valid @RequestPart("request") HorseRegistrationRequest input,
            @RequestPart(value="horseImage",required=false) MultipartFile image,
            @RequestPart(value="documents",required=false) List<MultipartFile> documents,
            @RequestPart(value="documentTypes",required=false) List<String> types) {
        return service.save(p.getName(),null,input,image,documents,types);
    }

    @PatchMapping(value="/horse-registration-requests/{id}",consumes="application/json")
    public Map<String,Object> supplementJson(Principal p,@PathVariable String id,@Valid @RequestBody HorseRegistrationRequest input) {
        return service.save(p.getName(),id,input,null,List.of(),List.of());
    }

    @PatchMapping(value="/horse-registration-requests/{id}",consumes="multipart/form-data")
    @Operation(summary="Supplement your pending registration",
        description="Omitted request fields remain unchanged. Documents are appended; horseImage replaces the current image. Same file limits as POST. Only the owner can read or supplement this request.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(
        encoding={@Encoding(name="request",contentType="application/json"),@Encoding(name="documentTypes",contentType="application/json")}))
    public Map<String,Object> supplementMultipart(Principal p,@PathVariable String id,
            @Valid @RequestPart(value="request",required=false) HorseRegistrationRequest input,
            @RequestPart(value="horseImage",required=false) MultipartFile image,
            @RequestPart(value="documents",required=false) List<MultipartFile> documents,
            @RequestPart(value="documentTypes",required=false) List<String> types) {
        return service.save(p.getName(),id,input,image,documents,types);
    }

    @GetMapping("/ownership-transfer-requests")
    public Map<String,Object> transfers(Principal principal) { return service.transfers(principal.getName()); }

    @PostMapping("/ownership-transfer-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> transfer(Principal principal,@Valid @RequestBody OwnershipTransferRequest input) {
        return service.transfer(principal.getName(),input);
    }
}
