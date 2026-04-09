package com.banking.userservice.repository;

import com.banking.userservice.entity.User;
import com.banking.userservice.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // Used by createUser to prevent duplicate registration
    boolean existsByEmail(String email);

    // Used by updateProfile: checks if another user (id != excludeId) owns the email
    // Spring Data parses: EXISTS WHERE email = ? AND id != ?
    boolean existsByEmailAndIdNot(String email, Long excludeId);

    // Used by GET /users/email/{email} — inter-service lookups need this
    Optional<User> findByEmail(String email);

    // Used by GET /users?status=ACTIVE — filters before paginating in the DB,
    // never loads the full table into memory
    Page<User> findAllByStatus(UserStatus status, Pageable pageable);
}
