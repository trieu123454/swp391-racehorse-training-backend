package com.example.springbootbackend.clubmanager.controller;

import com.example.springbootbackend.clubmanager.dto.LockUserRequest;
import com.example.springbootbackend.clubmanager.dto.PendingUserPageResponse;
import com.example.springbootbackend.clubmanager.dto.RejectUserRequest;
import com.example.springbootbackend.clubmanager.dto.RoleChangeActionRequest;
import com.example.springbootbackend.clubmanager.dto.UpdateUserRoleRequest;
import com.example.springbootbackend.clubmanager.dto.UserApprovalResponse;
import com.example.springbootbackend.clubmanager.service.ClubManagerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

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

    @PatchMapping("/users/{userId}/role")
    public Map<String, Object> updateRole(Principal principal, @PathVariable Long userId, @Valid @RequestBody UpdateUserRoleRequest request) {
        return service.updateRole(principal.getName(), userId, request.roleName());
    }
}
