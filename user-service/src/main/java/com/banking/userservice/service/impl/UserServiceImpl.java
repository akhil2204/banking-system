package com.banking.userservice.service.impl;

import com.banking.userservice.dto.CreateUserRequest;
import com.banking.userservice.dto.UpdateProfileRequest;
import com.banking.userservice.dto.UpdateStatusRequest;
import com.banking.userservice.dto.UserResponse;
import com.banking.userservice.entity.User;
import com.banking.userservice.entity.UserStatus;
import com.banking.userservice.exception.EmailAlreadyExistsException;
import com.banking.userservice.exception.UserNotFoundException;
import com.banking.userservice.repository.UserRepository;
import com.banking.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .build();

        return UserResponse.from(userRepository.save(user));
    }

    /*
     * Page<T> is the Spring Data pagination wrapper. It contains the content
     * slice plus metadata: totalElements, totalPages, current page number, etc.
     *
     * When status is null we return all users; otherwise we filter in the DB
     * with a WHERE clause — never load the full table and filter in memory.
     *
     * The caller controls page size and sort via Pageable, which Spring MVC
     * automatically binds from ?page=0&size=20&sort=createdAt,desc.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getAllUsers(UserStatus status, Pageable pageable) {
        if (status != null) {
            return userRepository.findAllByStatus(status, pageable).map(UserResponse::from);
        }
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    /*
     * Email uniqueness check on update must exclude the current user.
     * existsByEmail alone would false-positive when the user keeps their
     * own email. existsByEmailAndIdNot(email, id) translates to:
     *   SELECT COUNT(*) > 0 WHERE email = ? AND id != ?
     */
    @Override
    @Transactional
    public UserResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        if (userRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new EmailAlreadyExistsException(request.email());
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());

        return UserResponse.from(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse updateStatus(Long id, UpdateStatusRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        user.setStatus(request.status());
        return UserResponse.from(userRepository.save(user));
    }

    /*
     * Soft delete: status → DELETED. The row stays in the DB forever.
     * This satisfies audit requirements (who was this user?) and allows
     * account-service to still reference users by id without a FK violation.
     * Hard delete would cascade-break every linked account and transaction.
     */
    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);
    }
}
