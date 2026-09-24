package com.example.springbootbackend.auth.exception;

public class ApprovalPendingException extends AuthException {

    public ApprovalPendingException() {
        super("Account is awaiting Club Manager approval");
    }
}
