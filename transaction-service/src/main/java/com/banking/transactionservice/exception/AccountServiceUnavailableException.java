package com.banking.transactionservice.exception;

/*
 * Thrown by AccountClientFallbackFactory when the Resilience4j circuit breaker
 * for account-service is OPEN. Signals that no HTTP call was attempted — the
 * circuit rejected the request immediately to prevent cascading failures.
 *
 * TransactionServiceImpl catches this alongside FeignException so that
 * circuit-open failures produce FAILED transaction records (same audit trail
 * as real HTTP failures).
 *
 * GlobalExceptionHandler maps unhandled instances to 503 Service Unavailable.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
