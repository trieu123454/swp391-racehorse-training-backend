package com.example.springbootbackend.auth.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import com.example.springbootbackend.auth.dto.request.GoogleLoginRequest;
import com.example.springbootbackend.auth.dto.request.LoginRequest;
import com.example.springbootbackend.auth.dto.request.ChangePasswordRequest;
import com.example.springbootbackend.auth.dto.request.RefreshTokenRequest;
import com.example.springbootbackend.auth.dto.request.RegisterRequest;
import com.example.springbootbackend.auth.dto.response.AuthResponse;
import com.example.springbootbackend.auth.dto.response.UserResponse;
import com.example.springbootbackend.auth.entity.AppUser;
import com.example.springbootbackend.auth.entity.RefreshToken;
import com.example.springbootbackend.auth.entity.Role;
import com.example.springbootbackend.auth.entity.UserStatus;
import com.example.springbootbackend.auth.exception.AuthException;
import com.example.springbootbackend.auth.exception.ApprovalPendingException;
import com.example.springbootbackend.auth.repository.AppUserRepository;
import com.example.springbootbackend.auth.repository.RefreshTokenRepository;
import com.example.springbootbackend.auth.repository.RoleRepository;
import com.example.springbootbackend.security.JwtProperties;
import com.example.springbootbackend.security.JwtService;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private static final Set<String> REVIEWABLE_ROLES = Set.of("HEAD_TRAINER", "VETERINARIAN", "GROOM", "CLUB_MANAGER");

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
        if (!StringUtils.hasText(request.roleName()) || !"HORSE_OWNER".equalsIgnoreCase(request.roleName().trim())) {
            throw new AuthException("Chỉ Chủ ngựa có thể tự đăng ký. Vui lòng liên hệ Club Manager để được cấp tài khoản.");
        }
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new AuthException("Password must not exceed 72 UTF-8 bytes");
        }
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new AuthException("Email already exists");
        }

        Role role = findRole(request.roleName());
        AppUser user = new AppUser(
                request.fullName(),
                request.email().trim().toLowerCase(java.util.Locale.ROOT),
                request.phone(),
                passwordEncoder.encode(request.password()),
                role
        );

        boolean autoApprove = !requiresApproval(role);
        AppUser savedUser = userRepository.save(user);
        if (autoApprove) {
            savedUser.approve(null);
        }

        return UserResponse.from(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        if ("{google-login}".equals(request.password())
                || request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new AuthException("Email or password is incorrect");
        }
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
    public AuthResponse changePassword(String email, ChangePasswordRequest request) {
        AppUser user = findUser(email);
        ensureApproved(user);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AuthException("Current password is incorrect");
        }
        if (request.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new AuthException("Password must not exceed 72 UTF-8 bytes");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new AuthException("New password must be different from the current password");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        return createAuthResponse(user);
    }

    @Transactional(noRollbackFor = ApprovalPendingException.class)
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
        AppUser approver = requireClubManager(approverEmail);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        ensurePendingStaff(user);
        user.approve(approver);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse reject(Long userId, String approverEmail) {
        AppUser approver = requireClubManager(approverEmail);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        ensurePendingStaff(user);
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
        if (StringUtils.hasText(roleName) && !"HORSE_OWNER".equalsIgnoreCase(roleName.trim())) {
            throw new AuthException("Chỉ Chủ ngựa có thể tự đăng ký. Vui lòng liên hệ Club Manager để được cấp tài khoản.");
        }

        Role role = findRole("HORSE_OWNER");
        AppUser user = new AppUser(
                profile.fullName(),
                profile.email().trim().toLowerCase(java.util.Locale.ROOT),
                null,
                passwordEncoder.encode(UUID.randomUUID().toString()),
                role
        );

        boolean autoApprove = !requiresApproval(role);
        AppUser savedUser = userRepository.save(user);
        if (autoApprove) {
            savedUser.approve(null);
        }

        return savedUser;
    }

    private Role findRole(String roleName) {
        return roleRepository.findByName(roleName.trim().toUpperCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new AuthException("Role not found"));
    }

    private AppUser findUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthException("User not found"));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String email) {
        return UserResponse.from(findUser(email));
    }

    private void ensureApproved(AppUser user) {
        if (user.getDeletedAt() != null) {
            throw new AuthException("Account is deleted");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AuthException("Tài khoản đã bị khóa");
        }
        // Only pending horse owners can be automatically activated.
        // Rejected and locked accounts must never be automatically reactivated.
        if (user.getStatus() == UserStatus.PENDING && !requiresApproval(user.getRole())) {
            user.approve(null);
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new ApprovalPendingException();
        }
        if (user.getStatus() != UserStatus.APPROVED) {
            throw new AuthException("User is not approved");
        }
    }

    private boolean requiresApproval(Role role) {
        return !"HORSE_OWNER".equals(role.getName());
    }

    private AppUser requireClubManager(String email) {
        AppUser manager = findUser(email);
        ensureApproved(manager);
        if (manager.isMustChangePassword() || !"CLUB_MANAGER".equals(manager.getRole().getName())) {
            throw new AuthException("Only an active Club Manager can review accounts");
        }
        return manager;
    }

    private void ensurePendingStaff(AppUser user) {
        if (user.getDeletedAt() != null || user.getStatus() != UserStatus.PENDING
                || !REVIEWABLE_ROLES.contains(user.getRole().getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only pending staff accounts can be reviewed");
        }
    }
}
