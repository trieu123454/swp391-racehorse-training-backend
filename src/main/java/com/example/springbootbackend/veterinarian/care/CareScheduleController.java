package com.example.springbootbackend.veterinarian.care;

import com.example.springbootbackend.veterinarian.support.ApiPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
@io.swagger.v3.oas.annotations.tags.Tag(name="Veterinarian - Periodic care")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class CareScheduleController {
    private final CareScheduleService service;
    public CareScheduleController(CareScheduleService service) { this.service=service; }
    @PostMapping("/periodic-care-schedules") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(Principal p,HttpServletRequest r,@RequestBody JsonNode body) { return service.create(p.getName(),r.getRemoteAddr(),body); }
    @GetMapping("/periodic-care-schedules")
    public Map<String,Object> list(Principal p,@RequestParam(name="horse_id",required=false) UUID horse,
            @RequestParam(name="care_type",required=false) String care,@RequestParam(name="due_within_days",required=false) Integer days,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.list(p.getName(),horse==null?null:horse.toString(),care,days,new ApiPage(page,limit));
    }
    @GetMapping("/vet/calendar")
    public Map<String,Object> calendar(Principal p,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="mine") String scope,@RequestParam(name="horse_id",required=false) UUID horse,
            @RequestParam(name="care_type",required=false) String care,@RequestParam(name="include_context",defaultValue="false") boolean context,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        return service.calendar(p.getName(),from,to,scope,horse==null?null:horse.toString(),care,context,new ApiPage(page,limit));
    }
    @PostMapping("/periodic-care-schedules/{id}/book")
    public Map<String,Object> book(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) { return service.book(p.getName(),r.getRemoteAddr(),id.toString(),body); }
    @PatchMapping("/periodic-care-schedules/{id}/event")
    public Map<String,Object> move(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody JsonNode body) { return service.move(p.getName(),r.getRemoteAddr(),id.toString(),body); }
    @PostMapping("/periodic-care-schedules/{id}/cancel-event")
    public Map<String,Object> cancel(Principal p,HttpServletRequest r,@PathVariable UUID id) { return service.cancel(p.getName(),r.getRemoteAddr(),id.toString()); }
    @PostMapping("/periodic-care-schedules/{id}/complete")
    public Map<String,Object> complete(Principal p,HttpServletRequest r,@PathVariable UUID id,@RequestBody(required=false) JsonNode body) {
        return service.complete(p.getName(),r.getRemoteAddr(),id.toString(),body==null?JsonNodeFactory.instance.objectNode():body);
    }
}
