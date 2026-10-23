package com.example.springbootbackend.exception;

public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }
}
