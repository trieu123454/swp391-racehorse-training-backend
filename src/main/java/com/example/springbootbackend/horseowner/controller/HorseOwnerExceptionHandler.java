package com.example.springbootbackend.horseowner.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages="com.example.springbootbackend.horseowner")
public class HorseOwnerExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(Exception error) {
        return ResponseEntity.status(413).body(Map.of("message","File exceeds 10 MB or multipart request exceeds 56 MB"));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<?> malformed(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message","Missing or malformed JSON multipart part"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> business(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"Yêu cầu không hợp lệ":error.getReason()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        var fields=error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field",field.getField(),"message",String.valueOf(field.getDefaultMessage()))).toList();
        return ResponseEntity.badRequest().body(Map.of("message","Dữ liệu không hợp lệ","errors",fields));
    }
}
