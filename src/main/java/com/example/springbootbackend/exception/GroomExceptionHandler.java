package com.example.springbootbackend.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import com.example.springbootbackend.controller.GroomController;

@RestControllerAdvice(assignableTypes = GroomController.class)
public class GroomExceptionHandler {
    @ExceptionHandler(VetException.class)
    public ResponseEntity<?> business(VetException error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> responseStatus(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", error.getReason() == null ? "Yêu cầu không hợp lệ" : error.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> uploadTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(Map.of("message", "Ảnh không được vượt quá 5 MB"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> type(MethodArgumentTypeMismatchException error) {
        return business(VetException.invalid(error.getName(), "Sai định dạng dữ liệu"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> body(HttpMessageNotReadableException error) {
        return business(VetException.invalid("body", "JSON không hợp lệ hoặc thiếu nội dung"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<?> missing(Exception error) {
        return business(VetException.invalid("request", "Thiếu dữ liệu yêu cầu"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return business(VetException.invalid("request", "Giá trị không hợp lệ"));
    }
}
