package com.banking.accountservice.exception;

import com.banking.accountservice.entity.AccountStatus;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(String accountNumber, AccountStatus status) {
        super(String.format("Account %s is not active (current status: %s)", accountNumber, status));
    }
}
