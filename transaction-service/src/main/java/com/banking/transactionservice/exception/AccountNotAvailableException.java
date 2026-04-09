package com.banking.transactionservice.exception;

/*
 * Thrown when an account referenced in a transaction request cannot be used:
 * either it does not exist (Feign returned 404) or it is not ACTIVE.
 * Named "NotAvailable" rather than "NotFound" because the cause may be either.
 */
public class AccountNotAvailableException extends RuntimeException {

    public AccountNotAvailableException(String message) {
        super(message);
    }
}
