package com.example.springbootbackend.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.springbootbackend.entity.UserStatus;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.RefreshTokenRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticates incoming HTTP requests using Bearer JWT tokens. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final long AUTH_CACHE_TTL_MS = 15_000L;
    public record VerifiedActor(long userId, String role) {}
    private record CachedAuth(long userId, String role, long expiresAtMs) {}
    private static final ConcurrentHashMap<String, CachedAuth> AUTH_CACHE = new ConcurrentHashMap<>();

    public static VerifiedActor currentActor(String email) {
        if (email == null || email.isBlank()) return null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (!email.equalsIgnoreCase(auth.getName())) return null;
        if (!(auth.getDetails() instanceof Long userId)) return null;
        String role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
        return role == null ? null : new VerifiedActor(userId, role);
    }

    public static void invalidateEmail(String email) {
        if (email == null || email.isBlank()) return;
        String prefix = email.toLowerCase(Locale.ROOT) + "|";
        AUTH_CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static void invalidateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String suffix = "|" + sessionId;
        AUTH_CACHE.keySet().removeIf(k -> k.endsWith(suffix));
    }

    public static void invalidateAll() {
        AUTH_CACHE.clear();
    }

    private final JwtService jwtService;
    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository users, RefreshTokenRepository refreshTokens) {
        this.jwtService = jwtService;
        this.users = users;
        this.refreshTokens = refreshTokens;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            jwtService.validate(token).ifPresent(claims -> {
                if (!(claims.get("sub") instanceof String email)) return;
                if (!(claims.get("sessionId") instanceof String sessionId)) return;
                String cacheKey = email.toLowerCase(Locale.ROOT) + "|" + sessionId;
                long nowMs = System.currentTimeMillis();
                String reqPath = request.getRequestURI();
                boolean bypassCache = "/api/auth/me".equals(reqPath) || "/api/auth/me".equals(request.getServletPath());
                CachedAuth cached = bypassCache ? null : AUTH_CACHE.get(cacheKey);
                if (cached != null && nowMs < cached.expiresAtMs()
                        && String.valueOf(cached.userId()).equals(String.valueOf(claims.get("userId")))) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            email, null, List.of(new SimpleGrantedAuthority("ROLE_" + cached.role())));
                    authentication.setDetails(cached.userId());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    return;
                }
                var user = users.findByEmailIgnoreCase(email).orElse(null);
                if (user == null || user.getDeletedAt() != null
                        || !String.valueOf(user.getId()).equals(String.valueOf(claims.get("userId")))) return;
                if (!refreshTokens.existsBySessionIdAndRevokedFalseAndExpiresAtAfter(sessionId, LocalDateTime.now())) return;
                if (user.getStatus() != UserStatus.APPROVED) {
                    if (VetHttpSecurity.applies(request)) {
                        try {
                            VetHttpSecurity.write(response, 403,
                                    "FORBIDDEN", "Tài khoản không được phép truy cập");
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                        request.setAttribute("vetAccessDenied", Boolean.TRUE);
                    }
                    return;
                }
                if (user.isMustChangePassword() && !isAllowedDuringPasswordChange(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    try {
                        if (VetHttpSecurity.applies(request)) {
                            VetHttpSecurity.write(response, 403,
                                    "FORBIDDEN", "Cần đổi mật khẩu trước khi tiếp tục");
                        } else {
                            response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Change your password before continuing\"}");
                        }
                        response.flushBuffer();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    request.setAttribute("passwordChangeRequired", Boolean.TRUE);
                    return;
                }
                String role = user.getRole().getName();
                if (!user.isMustChangePassword()) {
                    AUTH_CACHE.put(cacheKey, new CachedAuth(user.getId(), role, nowMs + AUTH_CACHE_TTL_MS));
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                authentication.setDetails(user.getId());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        if (Boolean.TRUE.equals(request.getAttribute("passwordChangeRequired"))
                || Boolean.TRUE.equals(request.getAttribute("vetAccessDenied"))) return;

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedDuringPasswordChange(String path) {
        return "/api/auth/change-password".equals(path)
                || "/api/auth/me".equals(path)
                || "/api/auth/refresh".equals(path)
                || "/api/auth/logout".equals(path);
    }
}
