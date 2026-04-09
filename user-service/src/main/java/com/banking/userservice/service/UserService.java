package com.banking.userservice.service;

import com.banking.userservice.dto.CreateUserRequest;
import com.banking.userservice.dto.UpdateProfileRequest;
import com.banking.userservice.dto.UpdateStatusRequest;
import com.banking.userservice.dto.UserResponse;
import com.banking.userservice.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    Page<UserResponse> getAllUsers(UserStatus status, Pageable pageable);

    UserResponse getUserById(Long id);

    UserResponse getUserByEmail(String email);

    UserResponse updateProfile(Long id, UpdateProfileRequest request);

    UserResponse updateStatus(Long id, UpdateStatusRequest request);

    void deleteUser(Long id);
}
