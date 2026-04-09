package com.banking.userservice.dto;

import com.banking.userservice.entity.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(

        @NotNull(message = "Status is required")
        UserStatus status
) {}
