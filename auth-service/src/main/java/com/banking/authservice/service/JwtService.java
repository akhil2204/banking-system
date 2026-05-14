package com.banking.authservice.service;

import com.banking.authservice.entity.Credential;

import java.time.LocalDateTime;

public interface JwtService {

    String generate(Credential credential);

    LocalDateTime expiresAt();
}
