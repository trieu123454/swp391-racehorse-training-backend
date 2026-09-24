package com.example.springbootbackend.auth.controller;

import java.security.Principal;

import com.example.springbootbackend.auth.dto.request.GoogleLoginRequest;
import com.example.springbootbackend.auth.dto.request.LoginRequest;
import com.example.springbootbackend.auth.dto.request.LogoutRequest;
import com.example.springbootbackend.auth.dto.request.RefreshTokenRequest;
import com.example.springbootbackend.auth.dto.request.RegisterRequest;
import com.example.springbootbackend.auth.dto.response.AuthResponse;
import com.example.springbootbackend.auth.dto.response.MessageResponse;
import com.example.springbootbackend.auth.dto.response.UserResponse;
import com.example.springbootbackend.auth.exception.AuthException;
import com.example.springbootbackend.auth.service.AuthService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserResponse currentUser(Principal principal) {
        return authService.currentUser(principal.getName());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public MessageResponse handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return new MessageResponse(message);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public AuthResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.loginWithGoogle(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public MessageResponse logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return new MessageResponse("Logged out");
    }

    @PostMapping("/users/{userId}/approve")
    @PreAuthorize("hasRole('CLUB_MANAGER')")
    public UserResponse approve(@PathVariable Long userId, Principal principal) {
        return authService.approve(userId, principal.getName());
    }

    @PostMapping("/users/{userId}/reject")
    @PreAuthorize("hasRole('CLUB_MANAGER')")
    public UserResponse reject(@PathVariable Long userId, Principal principal) {
        return authService.reject(userId, principal.getName());
    }

    @ExceptionHandler(AuthException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public MessageResponse handleAuthException(AuthException ex) {
        return new MessageResponse(ex.getMessage());
    }
}
