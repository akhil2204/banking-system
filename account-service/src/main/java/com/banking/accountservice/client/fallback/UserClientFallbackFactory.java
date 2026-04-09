package com.banking.accountservice.client.fallback;

import com.banking.accountservice.dto.UserResponse;
import com.banking.accountservice.client.UserClient;
import com.banking.accountservice.exception.UserServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/*
 * FallbackFactory vs plain Fallback:
 *   A plain @Component fallback implements the Feign interface with hardcoded
 *   stub responses — it doesn't know WHY the call failed.
 *   A FallbackFactory receives the Throwable that caused the failure, so we
 *   can log the root cause and include it in the exception message for tracing.
 *
 * When is the fallback invoked?
 *   1. Circuit OPEN — Resilience4j rejects the call without making an HTTP request
 *      (cause = CallNotPermittedException)
 *   2. Circuit CLOSED/HALF-OPEN but call failed — after recording the failure,
 *      Resilience4j invokes the fallback instead of propagating the exception
 *      (cause = the original FeignException)
 */
@Slf4j
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        log.warn("user-service fallback triggered: {}", cause.getMessage());
        return id -> {
            throw new UserServiceUnavailableException(
                    "user-service is currently unavailable — please retry. Reason: " + cause.getMessage());
        };
    }
}
