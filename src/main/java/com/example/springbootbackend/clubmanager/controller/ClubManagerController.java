package com.example.springbootbackend.clubmanager.controller;

import com.example.springbootbackend.clubmanager.dto.LockUserRequest;
import com.example.springbootbackend.clubmanager.dto.CreateStaffUserRequest;
import com.example.springbootbackend.clubmanager.dto.PendingUserPageResponse;
import com.example.springbootbackend.clubmanager.dto.RejectUserRequest;
import com.example.springbootbackend.clubmanager.dto.RoleChangeActionRequest;
import com.example.springbootbackend.clubmanager.dto.UpdateUserRoleRequest;
import com.example.springbootbackend.clubmanager.dto.UserApprovalResponse;
import com.example.springbootbackend.clubmanager.service.ClubManagerService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/club-manager")
@PreAuthorize("hasRole('CLUB_MANAGER')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class ClubManagerController {
    private final ClubManagerService service;

    public ClubManagerController(ClubManagerService service) {
        this.service = service;
    }

    @GetMapping("/users/pending")
    public PendingUserPageResponse pendingUsers(
            Principal principal,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return service.pendingUsers(principal.getName(), role, page, limit);
    }

    @GetMapping("/users")
    public List<com.example.springbootbackend.clubmanager.dto.PendingUserResponse> users(
            Principal principal,
            @RequestParam(defaultValue = "APPROVED") String status,
            @RequestParam(required = false) String role) {
        return service.users(principal.getName(), status, role);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createStaffAccount(Principal principal, @Valid @RequestBody CreateStaffUserRequest request) {
        return service.createStaffAccount(principal.getName(), request);
    }

    @PatchMapping("/users/{userId}/approve")
    public UserApprovalResponse approve(Principal principal, @PathVariable Long userId) {
        return service.approve(principal.getName(), userId);
    }

    @PatchMapping("/users/{userId}/reject")
    public Map<String, Object> reject(Principal principal, @PathVariable Long userId, @Valid @RequestBody(required = false) RejectUserRequest request) {
        return service.reject(principal.getName(), userId, request == null ? null : request.reason());
    }

    @GetMapping("/role-change-requests")
    public List<Map<String, Object>> roleChangeRequests(Principal principal, @RequestParam(defaultValue = "Pending") String status) {
        return service.roleChangeRequests(principal.getName(), status);
    }

    @PatchMapping("/role-change-requests/{requestId}")
    public Map<String, Object> handleRoleChange(Principal principal, @PathVariable String requestId, @Valid @RequestBody RoleChangeActionRequest request) {
        return service.handleRoleChange(principal.getName(), requestId, request.action());
    }

    @PatchMapping("/users/{userId}/lock")
    public Map<String, Object> lock(Principal principal, @PathVariable Long userId, @Valid @RequestBody(required = false) LockUserRequest request) {
        return service.lock(principal.getName(), userId, request == null ? null : request.reason());
    }

    @PatchMapping("/users/{userId}/unlock")
    public Map<String, Object> unlock(Principal principal, @PathVariable Long userId) {
        return service.unlock(principal.getName(), userId);
    }

    @PatchMapping("/users/{userId}/role")
    public Map<String, Object> updateRole(Principal principal, @PathVariable Long userId, @Valid @RequestBody UpdateUserRoleRequest request) {
        return service.updateRole(principal.getName(), userId, request.roleName());
    }

    @GetMapping("/operations-report")
    public Map<String,Object> operationsReport(Principal principal,@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to) {
        return service.operationsReport(principal.getName(),from,to);
    }

    @GetMapping("/races")
    public Map<String,Object> officialRaces(Principal principal) {
        return service.officialRaces(principal.getName());
    }

    @PostMapping("/races")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createOfficialRace(Principal principal,HttpServletRequest request,@RequestBody JsonNode body) {
        return service.createOfficialRace(principal.getName(),request.getRemoteAddr(),body);
    }

    @GetMapping("/inventory-items")
    public Map<String,Object> inventoryItems(Principal principal) {
        return service.inventoryItems(principal.getName());
    }

    @PostMapping("/inventory-items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createInventoryItem(Principal principal,HttpServletRequest request,@RequestBody JsonNode body) {
        return service.createInventoryItem(principal.getName(),request.getRemoteAddr(),body);
    }

    @PatchMapping("/inventory-items/{id}")
    public Map<String,Object> updateInventoryItem(Principal principal,HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.updateInventoryItem(principal.getName(),request.getRemoteAddr(),id,body);
    }

    @GetMapping("/supply-requests")
    public Map<String,Object> supplyRequests(Principal principal,@RequestParam(defaultValue="Pending") String status) {
        return service.supplyRequests(principal.getName(),status);
    }

    @PatchMapping("/supply-requests/{id}")
    public Map<String,Object> reviewSupplyRequest(Principal principal,HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.reviewSupplyRequest(principal.getName(),request.getRemoteAddr(),id,body);
    }

    @GetMapping("/grooms")
    public List<Map<String,Object>> activeGrooms(Principal principal) {
        return service.activeGrooms(principal.getName());
    }

    @GetMapping("/audit-logs")
    public Map<String,Object> auditLogs(Principal principal,@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="50") int limit) {
        return service.auditLogs(principal.getName(),from,to,page,limit);
    }

    @GetMapping("/groom-tasks")
    public Map<String,Object> groomTasks(Principal principal,@RequestParam(required=false) LocalDate date) {
        return service.groomTasks(principal.getName(),date);
    }

    @PostMapping("/groom-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> assignGroomTask(Principal principal,HttpServletRequest request,@RequestBody JsonNode body) {
        return service.assignGroomTask(principal.getName(),request.getRemoteAddr(),body);
    }

    @PatchMapping("/groom-tasks/{id}")
    public Map<String,Object> updateGroomTask(Principal principal,HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body) {
        return service.updateGroomTask(principal.getName(),request.getRemoteAddr(),id,body);
    }

    @GetMapping("/groom-incidents")
    public Map<String,Object> incidents(Principal principal,@RequestParam(defaultValue="Pending") String status) {
        return service.incidents(principal.getName(),status);
    }

    @PatchMapping("/groom-incidents/{id}/resolve")
    public Map<String,Object> resolveIncident(Principal principal,HttpServletRequest request,@PathVariable UUID id) {
        return service.resolveIncident(principal.getName(),request.getRemoteAddr(),id);
    }

    @PostMapping("/financial-transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addFinancialTransaction(Principal principal,HttpServletRequest request,@RequestBody JsonNode body) {
        return service.addFinancialTransaction(principal.getName(),request.getRemoteAddr(),body);
    }
}
