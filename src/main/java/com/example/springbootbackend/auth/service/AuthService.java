package com.example.springbootbackend.auth.service;

import java.time.LocalDateTime;
import java.util.UUID;

import com.example.springbootbackend.auth.dto.request.GoogleLoginRequest;
import com.example.springbootbackend.auth.dto.request.LoginRequest;
import com.example.springbootbackend.auth.dto.request.RefreshTokenRequest;
import com.example.springbootbackend.auth.dto.request.RegisterRequest;
import com.example.springbootbackend.auth.dto.response.AuthResponse;
import com.example.springbootbackend.auth.dto.response.UserResponse;
import com.example.springbootbackend.auth.entity.AppUser;
import com.example.springbootbackend.auth.entity.RefreshToken;
import com.example.springbootbackend.auth.entity.Role;
import com.example.springbootbackend.auth.entity.UserStatus;
import com.example.springbootbackend.auth.exception.AuthException;
import com.example.springbootbackend.auth.repository.AppUserRepository;
import com.example.springbootbackend.auth.repository.RefreshTokenRepository;
import com.example.springbootbackend.auth.repository.RoleRepository;
import com.example.springbootbackend.security.JwtProperties;
import com.example.springbootbackend.security.JwtService;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private static final String GOOGLE_LOGIN_PASSWORD_PLACEHOLDER = "{google-login}";

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final GoogleTokenService googleTokenService;

    public AuthService(
            AppUserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            JwtProperties jwtProperties,
            GoogleTokenService googleTokenService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.googleTokenService = googleTokenService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new AuthException("Email already exists");
        }

        Role role = findRole(request.roleName());
        AppUser user = new AppUser(
                request.fullName(),
                request.email().trim().toLowerCase(),
                request.phone(),
                passwordEncoder.encode(request.password()),
                role
        );

        boolean autoApprove = shouldAutoApproveFirstClubManager(role);
        AppUser savedUser = userRepository.save(user);
        if (autoApprove) {
            savedUser.approve(savedUser);
        }

        return UserResponse.from(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
        } catch (BadCredentialsException ex) {
            throw new AuthException("Email or password is incorrect");
        }

        AppUser user = findUser(request.email());
        ensureApproved(user);
        return createAuthResponse(user);
    }

    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleTokenService.GoogleProfile profile = googleTokenService.verify(request.idToken());
        AppUser user = userRepository.findByEmailIgnoreCase(profile.email())
                .orElseGet(() -> createGoogleUser(profile, request.roleName()));

        ensureApproved(user);
        return createAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new AuthException("Refresh token is invalid"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException("Refresh token is expired or revoked");
        }

        ensureApproved(refreshToken.getUser());
        refreshToken.revoke();
        return createAuthResponse(refreshToken.getUser());
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue).ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public UserResponse approve(Long userId, String approverEmail) {
        AppUser approver = findUser(approverEmail);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        user.approve(approver);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse reject(Long userId, String approverEmail) {
        AppUser approver = findUser(approverEmail);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        user.reject(approver);
        return UserResponse.from(user);
    }

    private AuthResponse createAuthResponse(AppUser user) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshTokenValue = UUID.randomUUID().toString() + UUID.randomUUID();
        RefreshToken refreshToken = new RefreshToken(
                user,
                refreshTokenValue,
                LocalDateTime.now().plusDays(jwtProperties.refreshTokenDays())
        );
        refreshTokenRepository.save(refreshToken);
        return new AuthResponse(accessToken, refreshTokenValue, UserResponse.from(user));
    }

    private AppUser createGoogleUser(GoogleTokenService.GoogleProfile profile, String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw new AuthException("roleName is required for first Google login");
        }

        Role role = findRole(roleName);
        AppUser user = new AppUser(
                profile.fullName(),
                profile.email().trim().toLowerCase(),
                null,
                passwordEncoder.encode(GOOGLE_LOGIN_PASSWORD_PLACEHOLDER),
                role
        );

        boolean autoApprove = shouldAutoApproveFirstClubManager(role);
        AppUser savedUser = userRepository.save(user);
        if (autoApprove) {
            savedUser.approve(savedUser);
        }

        return savedUser;
    }

    private Role findRole(String roleName) {
        return roleRepository.findByName(roleName.trim().toUpperCase())
                .orElseThrow(() -> new AuthException("Role not found"));
    }

    private AppUser findUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthException("User not found"));
    }

    private void ensureApproved(AppUser user) {
        if (user.getStatus() != UserStatus.APPROVED) {
            throw new AuthException("User is not approved");
        }
    }

    private boolean shouldAutoApproveFirstClubManager(Role role) {
        return "CLUB_MANAGER".equals(role.getName()) && userRepository.count() == 0;
    }
}
