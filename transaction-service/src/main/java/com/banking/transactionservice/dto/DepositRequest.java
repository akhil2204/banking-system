package com.banking.transactionservice.dto;

import java.math.BigDecimal;

// Mirror of account-service's DepositRequest — used as Feign request body.
public record DepositRequest(BigDecimal amount) {}
