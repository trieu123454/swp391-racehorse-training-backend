package com.example.springbootbackend.groom;

import com.example.springbootbackend.veterinarian.support.VetException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.example.springbootbackend.groom")
public class GroomExceptionHandler {
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return business(VetException.invalid("request", "Giá trị không hợp lệ"));
    }
}
