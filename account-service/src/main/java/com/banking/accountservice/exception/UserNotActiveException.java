package com.banking.accountservice.exception;

public class UserNotActiveException extends RuntimeException {

    public UserNotActiveException(Long userId) {
        super("User " + userId + " is not active and cannot open an account");
    }
}
