package com.example.springbootbackend.exception;

import com.example.springbootbackend.controller.HorseController;
import com.example.springbootbackend.controller.HorseOwnerRegistrationController;
import com.example.springbootbackend.controller.HorseRegistrationReviewController;

import java.util.Map;
import java.util.Objects;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {HorseController.class, HorseOwnerRegistrationController.class, HorseRegistrationReviewController.class})
public class HorseExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> business(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Yêu cầu không hợp lệ" : e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .or(() -> e.getBindingResult().getAllErrors().stream()
                        .map(DefaultMessageSourceResolvable::getDefaultMessage)
                        .filter(Objects::nonNull)
                        .findFirst())
                .orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size(MaxUploadSizeExceededException e, jakarta.servlet.http.HttpServletRequest request) {
        String message = request.getRequestURI().contains("horse-registration-requests")
                ? "Tệp tải lên vượt giới hạn: ảnh ngựa tối đa 5 MB, mỗi giấy tờ tối đa 10 MB, toàn bộ yêu cầu tối đa 60 MB."
                : "Ảnh không được vượt quá 5 MB";
        return ResponseEntity.status(413).body(Map.of("message", message));
    }
}
