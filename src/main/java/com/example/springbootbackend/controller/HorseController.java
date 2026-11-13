package com.example.springbootbackend.controller;

import com.example.springbootbackend.dto.request.HorseRequest;
import com.example.springbootbackend.dto.request.HorseUpdateRequest;
import com.example.springbootbackend.service.HorseService;
import com.example.springbootbackend.service.HorseStorage;

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

    public HorseController(HorseService horses, HorseStorage storage) {
        this.horses = horses;
        this.storage = storage;
    }

    @GetMapping
    public Map<String, Object> list(Principal principal, @RequestParam(name = "search", required = false) String search,
                                    @RequestParam(name = "breed", required = false) String breed, @RequestParam(name = "currentStatus", required = false) String currentStatus,
                                    @RequestParam(name = "status", required = false) String status, @RequestParam(name = "stableBoxId", required = false) UUID stableBoxId,
                                    @RequestParam(name = "stable_box_id", required = false) UUID stableBoxIdSnake,
                                    @RequestParam(name = "isTrainingLocked", required = false) Boolean isTrainingLocked, @RequestParam(name = "is_locked", required = false) Boolean lockedSnake,
                                    @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size,
                                    @RequestParam(name = "limit", required = false) Integer limit) {
        UUID stable = stableBoxId != null ? stableBoxId : stableBoxIdSnake;
        Boolean locked = isTrainingLocked != null ? isTrainingLocked : lockedSnake;
        int actualPage = page < 1 ? 0 : page - 1;
        int actualSize = limit == null ? size : limit;
        Map<String, Object> res = horses.list(horses.actor(principal.getName(), false), search, breed, currentStatus != null ? currentStatus : status, stable, locked, actualPage, actualSize);
        if (res.get("items") instanceof List<?> listItems) {
            for (Object obj : listItems) {
                if (obj instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) rawMap;
                    attachSignedImageUrl(row);
                }
            }
        }
        return res;
    }

    private void attachSignedImageUrl(Map<String, Object> horse) {
        Object imgObj = horse.get("image_url");
        if (imgObj instanceof String path && !path.isBlank()) {
            try {
                Map<String, Object> signed = storage.signedUrl(path);
                if (signed.get("url") instanceof String signedUrl && !signedUrl.isBlank()) {
                    horse.put("image_signed_url", signedUrl);
                }
            } catch (Exception ignored) {
                // Keep non-blocking if storage signing fails for an item
            }
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(Principal p, @PathVariable("id") UUID id) {
        HorseService.Actor actor = horses.actor(p.getName(), false);
        Map<String, Object> horse = horses.detail(actor, id);
        attachSignedImageUrl(horse);
        if (Set.of("CLUB_MANAGER", "HORSE_OWNER").contains(actor.role())) {
            List<Map<String, Object>> documents = new ArrayList<>();
            for (Map<String, Object> document : horses.approvedRegistrationDocumentsForVerifiedHorse(actor, id)) {
                Map<String, Object> item = new LinkedHashMap<>(document);
                String path = Objects.toString(item.remove("object_path"), null);
                Map<String, Object> signed = storage.signedUrl(path);
                Map<String, Object> download = storage.signedUrl(path, Objects.toString(item.get("name"), "giay-to.pdf"));
                item.put("url", signed.get("url"));
                item.put("download_url", download.get("url"));
                item.put("expires_in", signed.get("expiresIn"));
                documents.add(item);
            }
            horse.put("registration_documents", documents);
        }
        return horse;
    }

    @GetMapping("/{id}/ownership-history")
    public List<Map<String, Object>> ownershipHistory(Principal p, @PathVariable("id") UUID id) {
        return horses.ownershipHistory(horses.actor(p.getName(), true), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Principal p, @Valid @RequestBody HorseRequest r) {
        return horses.save(horses.actor(p.getName(), true), null, r);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(Principal p, @PathVariable("id") UUID id, @Valid @RequestBody HorseUpdateRequest r) {
        return horses.update(horses.actor(p.getName(), true), id, r);
    }

    @GetMapping("/options/stables")
    public List<Map<String, Object>> stables(Principal p) {
        horses.actor(p.getName(), false);
        return horses.stables();
    }

    @GetMapping("/options/owners")
    public Map<String, Object> owners(Principal p,
                                      @RequestParam(name = "q", defaultValue = "") String q,
                                      @RequestParam(name = "page", defaultValue = "0") int page,
                                      @RequestParam(name = "size", defaultValue = "20") int size) {
        horses.ownerLookupActor(p.getName());
        return horses.owners(q, page, size);
    }

    @GetMapping("/{id}/deletion-warnings")
    public Map<String, Object> warnings(Principal p, @PathVariable("id") UUID id) {
        return horses.warnings(horses.actor(p.getName(), true), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Principal p, @PathVariable("id") UUID id, @RequestParam(name = "confirmed", defaultValue = "false") boolean confirmed) {
        return horses.delete(horses.actor(p.getName(), true), id, confirmed);
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(Principal p, @RequestParam("file") MultipartFile file) {
        return storage.upload(file, horses.actor(p.getName(), true).id());
    }

    @GetMapping("/{id}/image")
    public Map<String, Object> image(Principal p, @PathVariable("id") UUID id) {
        return storage.signedUrl((String) horses.detail(horses.actor(p.getName(), false), id).get("image_url"));
    }
}
