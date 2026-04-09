package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(

        @NotNull(message = "User ID is required")
        Long userId,

        @NotNull(message = "Account type is required")
        AccountType type
) {}
