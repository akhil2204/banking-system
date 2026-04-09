package com.banking.accountservice.exception;

/*
 * Thrown by UserClientFallbackFactory when the circuit breaker for
 * user-service is OPEN. Unlike FeignException (which signals a live HTTP
 * error), this exception means no call was attempted — Resilience4j rejected
 * it immediately to prevent cascading failures while user-service recovers.
 *
 * GlobalExceptionHandler maps this to 503 Service Unavailable.
 */
public class UserServiceUnavailableException extends RuntimeException {

    public UserServiceUnavailableException(String message) {
        super(message);
    }
}
