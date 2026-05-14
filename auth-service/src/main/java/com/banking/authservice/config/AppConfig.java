package com.banking.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class AppConfig {

    /*
     * BCryptPasswordEncoder is the only Spring Security component we use.
     * We do NOT import spring-boot-starter-security — just spring-security-crypto.
     * This gives us BCrypt hashing with zero security auto-configuration:
     * no login page, no HTTP Basic, no filter chain, no default user.
     *
     * Cost factor 10 (default): BCrypt performs 2^10 = 1024 iterations.
     * Each password check takes ~100ms on modern hardware — fast enough for
     * legitimate logins, slow enough to make offline brute-force impractical.
     * Increase to 12–13 as hardware gets faster.
     */
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
