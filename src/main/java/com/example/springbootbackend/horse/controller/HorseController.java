package com.example.springbootbackend.horse.controller;

import com.example.springbootbackend.horse.dto.request.HorseRequest;
import com.example.springbootbackend.horse.dto.request.HorseUpdateRequest;
import com.example.springbootbackend.horse.service.HorseService;
import com.example.springbootbackend.horse.storage.HorseStorage;

import java.security.Principal;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/horses")
public class HorseController {
    private final HorseService horses;
    private final HorseStorage storage;
    public HorseController(HorseService horses,HorseStorage storage) { this.horses=horses; this.storage=storage; }
    @GetMapping
    public Map<String,Object> list(Principal principal,@RequestParam(required=false) String search,
        @RequestParam(required=false) String breed,@RequestParam(required=false) String currentStatus,
        @RequestParam(required=false) String status,@RequestParam(required=false) UUID stableBoxId,
        @RequestParam(name="stable_box_id", required=false) UUID stableBoxIdSnake,
        @RequestParam(required=false) Boolean isTrainingLocked,@RequestParam(name="is_locked", required=false) Boolean lockedSnake,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(name="limit", required=false) Integer limit) {
        UUID stable = stableBoxId != null ? stableBoxId : stableBoxIdSnake;
        Boolean locked = isTrainingLocked != null ? isTrainingLocked : lockedSnake;
        int actualPage = page < 1 ? 0 : page - 1;
        int actualSize = limit == null ? size : limit;
        return horses.list(horses.actor(principal.getName(),false),search,breed,currentStatus != null ? currentStatus : status,stable,locked,actualPage,actualSize);
    }
    @GetMapping("/{id}")
    public Map<String,Object> detail(Principal p,@PathVariable UUID id) { return horses.detail(horses.actor(p.getName(),false),id); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(Principal p,@Valid @RequestBody HorseRequest r) { return horses.save(horses.actor(p.getName(),true),null,r); }
    @PutMapping("/{id}")
    public Map<String,Object> update(Principal p,@PathVariable UUID id,@Valid @RequestBody HorseUpdateRequest r) { return horses.update(horses.actor(p.getName(),true),id,r); }
    @GetMapping("/options/stables")
    public List<Map<String,Object>> stables(Principal p) { horses.actor(p.getName(),false); return horses.stables(); }
    @GetMapping("/options/owners")
    public List<Map<String,Object>> owners(Principal p) { horses.actor(p.getName(),true); return horses.owners(); }
    @GetMapping("/{id}/deletion-warnings")
    public Map<String,Object> warnings(Principal p,@PathVariable UUID id) { return horses.warnings(horses.actor(p.getName(),true),id); }
    @DeleteMapping("/{id}")
    public Map<String,Object> delete(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="false") boolean confirmed) { return horses.delete(horses.actor(p.getName(),true),id,confirmed); }
    @PostMapping(value="/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(Principal p,@RequestParam MultipartFile file) { return storage.upload(file,horses.actor(p.getName(),true).id()); }
    @GetMapping("/{id}/image")
    public Map<String,Object> image(Principal p,@PathVariable UUID id) { return storage.signedUrl((String)horses.detail(horses.actor(p.getName(),false),id).get("image_url")); }
}
