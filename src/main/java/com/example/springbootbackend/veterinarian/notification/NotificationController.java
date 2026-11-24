package com.example.springbootbackend.veterinarian.notification;

import com.example.springbootbackend.veterinarian.support.ApiPage;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Notifications")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationService service;
    private final NotificationStreamService streams;
    public NotificationController(NotificationService service, NotificationStreamService streams) { this.service = service; this.streams = streams; }

    @GetMapping
    public Map<String, Object> list(Principal user, @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.list(user.getName(), unreadOnly, new ApiPage(page, limit));
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> read(Principal user, HttpServletRequest request, @PathVariable UUID id) {
        return service.read(user.getName(), request.getRemoteAddr(), id);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(Principal user, HttpServletRequest request) {
        return service.readAll(user.getName(), request.getRemoteAddr());
    }

    @GetMapping(value="/stream", produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(Principal user) {
        return streams.open(user.getName());
    }

    @GetMapping("/{id}/target")
    public Map<String,Object> target(Principal user,@PathVariable UUID id) {
        return service.target(user.getName(),id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(Principal user,HttpServletRequest request,@PathVariable UUID id) {
        service.delete(user.getName(),request.getRemoteAddr(),id);
    }
}
