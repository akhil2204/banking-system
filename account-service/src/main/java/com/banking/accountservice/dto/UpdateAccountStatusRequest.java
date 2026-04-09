package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(

        @NotNull(message = "Status is required")
        AccountStatus status
) {}
