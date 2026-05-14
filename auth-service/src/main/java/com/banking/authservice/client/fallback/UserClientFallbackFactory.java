package com.banking.authservice.client.fallback;

import com.banking.authservice.client.UserClient;
import com.banking.authservice.exception.UserServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        log.warn("user-service fallback triggered during registration: {}", cause.getMessage());
        return request -> {
            throw new UserServiceUnavailableException(
                    "user-service is unavailable — cannot complete registration. Reason: " + cause.getMessage());
        };
    }
}
