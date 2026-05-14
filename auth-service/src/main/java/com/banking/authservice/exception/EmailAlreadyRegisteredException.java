package com.banking.authservice.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("Email is already registered: " + email);
    }
}
