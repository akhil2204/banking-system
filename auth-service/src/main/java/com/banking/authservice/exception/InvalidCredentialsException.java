package com.banking.authservice.exception;

/*
 * Thrown when email not found OR password does not match.
 * We intentionally use the same message for both cases — telling a caller
 * "email not found" versus "wrong password" is a user-enumeration vulnerability
 * that lets attackers discover which emails are registered.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
