package com.example.springbootbackend.veterinarian.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class VetHttpSecurity {
    private static final ObjectMapper JSON = new ObjectMapper();
    private VetHttpSecurity() {}
    public static boolean applies(String path) {
        return path.matches("/api/horses/[^/]+/(health-exams|medical-records|prescriptions|diet-records|injury-markers|health-status|training-lock|training-unlock)/?")
                || path.matches("/api/horses/[^/]+/(training-metrics|training-plans|training-schedules|race-entries)/?")
                || path.startsWith("/api/head-trainer/") || path.startsWith("/api/training-plans/")
                || path.startsWith("/api/training-schedules/")
                || path.equals("/api/groom") || path.startsWith("/api/groom/")
                || path.startsWith("/api/vet/") || path.startsWith("/api/periodic-care-schedules")
                || path.startsWith("/api/medical-records/") || path.startsWith("/api/prescriptions/") || path.startsWith("/api/diet-records/")
                || path.equals("/api/health-exams") || path.startsWith("/api/health-exams/")
                || path.equals("/api/notifications") || path.startsWith("/api/notifications/");
    }

    public static boolean applies(jakarta.servlet.http.HttpServletRequest request) {
        return applies(request.getRequestURI().substring(request.getContextPath().length()));
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(JSON.writeValueAsString(new VetException(status, code, message).body()));
    }
}
