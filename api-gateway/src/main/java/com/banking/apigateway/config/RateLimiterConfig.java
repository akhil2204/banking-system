package com.banking.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Configuration
public class RateLimiterConfig {

    /**
     * Rate-limit key = client IP address.
     *
     * In production behind a reverse proxy, replace with X-Forwarded-For:
     *   exchange.getRequest().getHeaders()
     *       .getFirst("X-Forwarded-For")
     *
     * IP-based keying is correct for direct access (local dev, Docker Compose).
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                Objects.requireNonNull(
                        exchange.getRequest().getRemoteAddress(),
                        "Remote address is null — check network config"
                ).getAddress().getHostAddress()
        );
    }
}
