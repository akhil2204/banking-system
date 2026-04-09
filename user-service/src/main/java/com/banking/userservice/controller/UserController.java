package com.banking.userservice.controller;

import com.banking.userservice.dto.CreateUserRequest;
import com.banking.userservice.dto.UpdateProfileRequest;
import com.banking.userservice.dto.UpdateStatusRequest;
import com.banking.userservice.dto.UserResponse;
import com.banking.userservice.entity.UserStatus;
import com.banking.userservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "User lifecycle management")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Create a new user",
               responses = {
                   @ApiResponse(responseCode = "201", description = "User created"),
                   @ApiResponse(responseCode = "400", description = "Validation failed"),
                   @ApiResponse(responseCode = "409", description = "Email already in use")
               })
    @PostMapping
    ResponseEntity<UserResponse> createUser(@RequestBody @Valid CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
    }

    @Operation(summary = "List all users (paginated)",
               description = "Supports ?status=ACTIVE|SUSPENDED|DELETED filter. " +
                             "Pagination: ?page=0&size=20&sort=createdAt,desc",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Page of users returned")
               })
    @GetMapping
    ResponseEntity<Page<UserResponse>> getAllUsers(
            @Parameter(description = "Filter by status (optional)")
            @RequestParam(required = false) UserStatus status,
            /*
             * @PageableDefault sets the fallback when the caller doesn't supply
             * pagination params. Without it, Spring defaults to page=0, size=20,
             * unsorted — fine in dev but unpredictable in production.
             */
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(userService.getAllUsers(status, pageable));
    }

    @Operation(summary = "Get user by ID",
               responses = {
                   @ApiResponse(responseCode = "200", description = "User found"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    @GetMapping("/{id}")
    ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @Operation(summary = "Get user by email",
               description = "Used by other services (account-service, etc.) to resolve a user by email address.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "User found"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    @GetMapping("/email/{email}")
    ResponseEntity<UserResponse> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }

    @Operation(summary = "Update user profile (name + email)",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Profile updated"),
                   @ApiResponse(responseCode = "400", description = "Validation failed"),
                   @ApiResponse(responseCode = "404", description = "User not found"),
                   @ApiResponse(responseCode = "409", description = "Email already in use by another user")
               })
    @PutMapping("/{id}")
    ResponseEntity<UserResponse> updateProfile(
            @PathVariable Long id,
            @RequestBody @Valid UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(id, request));
    }

    @Operation(summary = "Update user status",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Status updated"),
                   @ApiResponse(responseCode = "400", description = "Invalid status value"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    @PutMapping("/{id}/status")
    ResponseEntity<UserResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid UpdateStatusRequest request) {
        return ResponseEntity.ok(userService.updateStatus(id, request));
    }

    @Operation(summary = "Soft-delete a user (sets status to DELETED)",
               responses = {
                   @ApiResponse(responseCode = "204", description = "User deleted"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
