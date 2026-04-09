package com.banking.userservice.exception;

/*
 * Unchecked exception: callers don't declare it in their signatures.
 * The GlobalExceptionHandler catches it and maps it to 404 — so there
 * is no try/catch anywhere in service or controller code. The exception
 * carries its own descriptive message; the handler just reads it.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found with id: " + id);
    }

    public UserNotFoundException(String email) {
        super("User not found with email: " + email);
    }
}
