package com.banking.authservice.service.impl;

import com.banking.authservice.client.UserClient;
import com.banking.authservice.dto.*;
import com.banking.authservice.entity.Credential;
import com.banking.authservice.exception.EmailAlreadyRegisteredException;
import com.banking.authservice.exception.InvalidCredentialsException;
import com.banking.authservice.repository.CredentialRepository;
import com.banking.authservice.service.AuthService;
import com.banking.authservice.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final CredentialRepository credentialRepository;
    private final UserClient userClient;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 1. Guard: reject duplicate email before touching user-service
        if (credentialRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyRegisteredException(request.email());
        }

        // 2. Create the user profile in user-service via Feign.
        //    If user-service is down, UserClientFallbackFactory throws
        //    UserServiceUnavailableException → 503.
        //    If user-service returns 409 (email already in user DB),
        //    FeignException.Conflict propagates to GlobalExceptionHandler → 409.
        UserResponse user = userClient.createUser(
                new CreateUserRequest(request.firstName(), request.lastName(), request.email()));

        log.info("User created in user-service: userId={} email={}", user.id(), user.email());

        // 3. Hash the password — BCrypt with cost factor 10 (default).
        //    Never store or log the raw password.
        String hash = passwordEncoder.encode(request.password());

        // 4. Persist credential record in auth-service's own DB.
        Credential credential = credentialRepository.save(
                Credential.builder()
                        .email(request.email())
                        .passwordHash(hash)
                        .userId(user.id())
                        .build()
        );

        log.info("Credential stored for userId={}", credential.getUserId());

        // 5. Issue JWT and return.
        String token = jwtService.generate(credential);
        return buildResponse(token, credential);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        /*
         * Look up by email. If not found, throw the SAME exception as wrong password.
         * This prevents user enumeration: an attacker cannot tell from the response
         * whether the email exists or the password was wrong.
         */
        Credential credential = credentialRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        // BCrypt matches: hashes the raw password and compares with the stored hash.
        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            log.warn("Failed login attempt for email={}", request.email());
            throw new InvalidCredentialsException();
        }

        log.info("Login successful for userId={}", credential.getUserId());

        String token = jwtService.generate(credential);
        return buildResponse(token, credential);
    }

    private AuthResponse buildResponse(String token, Credential credential) {
        return new AuthResponse(
                token,
                "Bearer",
                credential.getUserId(),
                credential.getEmail(),
                credential.getRole().name(),
                credential.getCreatedAt(),
                jwtService.expiresAt()
        );
    }
}
