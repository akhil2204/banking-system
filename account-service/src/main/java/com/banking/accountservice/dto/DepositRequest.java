package com.banking.accountservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(

        /*
         * @DecimalMin("0.01") rather than @Positive: @Positive allows any value > 0
         * including 0.0000001. A minimum deposit of 0.01 (1 cent) is a meaningful
         * business rule that prevents noise transactions.
         */
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Deposit amount must be at least 0.01")
        BigDecimal amount
) {}
