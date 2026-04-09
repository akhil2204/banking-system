package com.banking.accountservice.client;

import com.banking.accountservice.client.fallback.UserClientFallbackFactory;
import com.banking.accountservice.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/*
 * fallbackFactory = UserClientFallbackFactory.class wires in the circuit breaker
 * fallback. When user-service is unreachable or the circuit is OPEN, Resilience4j
 * calls the factory instead of making the HTTP request.
 *
 * Without a fallbackFactory, an OPEN circuit throws CallNotPermittedException,
 * which would propagate all the way to the controller as an unhandled exception.
 * The factory gives us the chance to convert it into a domain exception with a
 * meaningful message before it reaches GlobalExceptionHandler.
 */
@FeignClient(name = "user-service", fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    @GetMapping("/users/{id}")
    UserResponse getUserById(@PathVariable Long id);
}
