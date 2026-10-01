package com.example.springbootbackend.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final com.example.springbootbackend.auth.repository.AppUserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService,
            com.example.springbootbackend.auth.repository.AppUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            jwtService.validate(token).ifPresent(claims -> {
                if (!(claims.get("sub") instanceof String email)) return;
                var user = users.findByEmailIgnoreCase(email).orElse(null);
                if (user == null || user.getDeletedAt() != null
                        || !String.valueOf(user.getId()).equals(String.valueOf(claims.get("userId")))) return;
                if (user.getStatus() != com.example.springbootbackend.auth.entity.UserStatus.APPROVED) {
                    if (com.example.springbootbackend.veterinarian.support.VetHttpSecurity.applies(request)) {
                        try {
                            com.example.springbootbackend.veterinarian.support.VetHttpSecurity.write(response, 403,
                                    "FORBIDDEN", "Tài khoản không được phép truy cập");
                        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
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
                        if (com.example.springbootbackend.veterinarian.support.VetHttpSecurity.applies(request)) {
                            com.example.springbootbackend.veterinarian.support.VetHttpSecurity.write(response, 403,
                                    "FORBIDDEN", "Cần đổi mật khẩu trước khi tiếp tục");
                        } else {
                            response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Change your password before continuing\"}");
                        }
                        response.flushBuffer();
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                    request.setAttribute("passwordChangeRequired", Boolean.TRUE);
                    return;
                }
                String role = user.getRole().getName();
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
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
