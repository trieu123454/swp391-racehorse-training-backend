package com.example.springbootbackend.veterinarian.support;

import java.util.List;
import java.util.Map;

public class VetException extends RuntimeException {
    private final int status;
    private final String code;
    private final Object details;

    public VetException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public VetException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() { return status; }
    public Map<String, Object> body() {
        return Map.of("error", Map.of("code", code, "message", getMessage(), "details", details));
    }

    public static VetException invalid(String field, String message) {
        return new VetException(400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ",
                List.of(Map.of("field", field, "message", message)));
    }
}
