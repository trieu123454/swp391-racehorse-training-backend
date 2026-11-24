package com.example.springbootbackend.clubmanager.exception;

import com.example.springbootbackend.clubmanager.controller.ClubManagerController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(assignableTypes = ClubManagerController.class)
public class ClubManagerExceptionHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,String>> invalidInput(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message","Invalid action, identifier or JSON body"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> business(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "Yêu cầu không hợp lệ" : exception.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
