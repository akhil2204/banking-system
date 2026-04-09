package com.banking.transactionservice.dto;

import java.math.BigDecimal;

// Mirror of account-service's WithdrawRequest — used as Feign request body.
public record WithdrawRequest(BigDecimal amount) {}
