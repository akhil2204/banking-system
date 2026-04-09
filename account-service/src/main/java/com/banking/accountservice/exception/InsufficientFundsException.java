package com.banking.accountservice.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String accountNumber, BigDecimal requested, BigDecimal available) {
        super(String.format(
                "Insufficient funds in account %s: requested %.2f, available %.2f",
                accountNumber, requested, available
        ));
    }
}
