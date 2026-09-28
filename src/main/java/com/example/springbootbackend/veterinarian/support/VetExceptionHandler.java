package com.example.springbootbackend.veterinarian.support;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.example.springbootbackend.veterinarian")
public class VetExceptionHandler {
    @ExceptionHandler(VetException.class)
    public ResponseEntity<?> business(VetException error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> type(MethodArgumentTypeMismatchException error) {
        return business(VetException.invalid(error.getName(), "Sai định dạng dữ liệu"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> body(HttpMessageNotReadableException error) {
        return business(VetException.invalid("body", "JSON không hợp lệ hoặc thiếu nội dung"));
    }
}
