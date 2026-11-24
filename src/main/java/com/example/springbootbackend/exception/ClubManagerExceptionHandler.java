package com.example.springbootbackend.exception;

import com.example.springbootbackend.controller.ClubManagerController;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ClubManagerController.class)
public class ClubManagerExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ClubManagerExceptionHandler.class);

  @ExceptionHandler(VetException.class)
  ResponseEntity<?> inventoryBusiness(VetException exception) {
    return ResponseEntity.status(exception.status()).body(exception.body());
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> business(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .body(
            Map.of(
                "message",
                exception.getReason() == null ? "Yêu cầu không hợp lệ" : exception.getReason()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, String>> forbidden(AccessDeniedException exception) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(Map.of("message", "Không có quyền truy cập"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse("Dữ liệu không hợp lệ");
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<Map<String, String>> malformed(Exception exception) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Dữ liệu hoặc tham số yêu cầu không hợp lệ"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> general(Exception exception) {
    log.error("Lỗi xử lý ClubManager: ", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            Map.of(
                "message",
                exception.getMessage() == null
                    ? "Có lỗi xảy ra trong quá trình xử lý"
                    : exception.getMessage()));
  }
}
