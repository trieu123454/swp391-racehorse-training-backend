package com.example.springbootbackend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.springbootbackend.controller.CareScheduleController;
import com.example.springbootbackend.controller.HealthExamController;
import com.example.springbootbackend.controller.MedicalController;
import com.example.springbootbackend.controller.NotificationController;
import com.example.springbootbackend.controller.VetHorseController;

@RestControllerAdvice(assignableTypes = {
        CareScheduleController.class,
        HealthExamController.class,
        MedicalController.class,
        NotificationController.class,
        VetHorseController.class
})
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
