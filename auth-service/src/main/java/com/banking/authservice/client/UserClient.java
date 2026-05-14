package com.banking.authservice.client;

import com.banking.authservice.client.fallback.UserClientFallbackFactory;
import com.banking.authservice.dto.CreateUserRequest;
import com.banking.authservice.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/*
 * Calls user-service to create the user profile during registration.
 * "user-service" must match spring.application.name in user-service's application.yml.
 * Eureka resolves lb://user-service to an actual host:port at runtime.
 *
 * We only need POST /users here — auth-service doesn't query user data after creation.
 */
@FeignClient(name = "user-service", fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    @PostMapping("/users")
    UserResponse createUser(@RequestBody CreateUserRequest request);
}
