package com.banking.accountservice.dto;

import java.math.BigDecimal;

/*
 * Dedicated balance response — lighter than returning the full AccountResponse
 * when transaction-service only needs to check the current balance.
 */
public record BalanceResponse(
        Long accountId,
        String accountNumber,
        BigDecimal balance
) {}
