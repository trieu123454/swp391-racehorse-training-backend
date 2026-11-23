package com.example.springbootbackend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.springbootbackend.controller.HeadTrainerController;

@RestControllerAdvice(assignableTypes = HeadTrainerController.class)
public class HeadTrainerExceptionHandler {
    @ExceptionHandler(VetException.class)
    public ResponseEntity<?> business(VetException error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> type(MethodArgumentTypeMismatchException error) {
        return business(VetException.invalid(error.getName(), "Sai dinh dang du lieu"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> body(HttpMessageNotReadableException error) {
        return business(VetException.invalid("body", "JSON khong hop le hoac thieu noi dung"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> missing(MissingServletRequestParameterException error) {
        return business(VetException.invalid(error.getParameterName(), "Thiếu tham số bắt buộc"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return business(VetException.invalid("request", "Giá trị không hợp lệ"));
    }
}
