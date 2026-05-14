package com.banking.authservice.service;

import com.banking.authservice.dto.AuthResponse;
import com.banking.authservice.dto.LoginRequest;
import com.banking.authservice.dto.RegisterRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}
