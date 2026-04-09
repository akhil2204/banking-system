package com.banking.accountservice.controller;

import com.banking.accountservice.dto.*;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Accounts", description = "Bank account lifecycle and balance operations")
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Open a new bank account",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Account created"),
                   @ApiResponse(responseCode = "404", description = "User not found"),
                   @ApiResponse(responseCode = "422", description = "User is not active")
               })
    @PostMapping
    ResponseEntity<AccountResponse> createAccount(@RequestBody @Valid CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @Operation(summary = "List all accounts (paginated, optional ?status= filter)")
    @GetMapping
    ResponseEntity<Page<AccountResponse>> getAllAccounts(
            @RequestParam(required = false) AccountStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(accountService.getAllAccounts(status, pageable));
    }

    @Operation(summary = "Get account by ID",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Account found"),
                   @ApiResponse(responseCode = "404", description = "Account not found")
               })
    @GetMapping("/{id}")
    ResponseEntity<AccountResponse> getAccountById(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountById(id));
    }

    @Operation(summary = "Get account by account number")
    @GetMapping("/number/{accountNumber}")
    ResponseEntity<AccountResponse> getAccountByNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getAccountByNumber(accountNumber));
    }

    @Operation(summary = "Get all accounts belonging to a user")
    @GetMapping("/user/{userId}")
    ResponseEntity<Page<AccountResponse>> getAccountsByUserId(
            @PathVariable Long userId,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(accountService.getAccountsByUserId(userId, pageable));
    }

    @Operation(summary = "Get current balance")
    @GetMapping("/{id}/balance")
    ResponseEntity<BalanceResponse> getBalance(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getBalance(id));
    }

    @Operation(summary = "Deposit funds",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Deposit successful"),
                   @ApiResponse(responseCode = "400", description = "Invalid amount"),
                   @ApiResponse(responseCode = "404", description = "Account not found"),
                   @ApiResponse(responseCode = "409", description = "Concurrent modification — retry"),
                   @ApiResponse(responseCode = "422", description = "Account is not active")
               })
    @PutMapping("/{id}/deposit")
    ResponseEntity<AccountResponse> deposit(
            @PathVariable Long id,
            @RequestBody @Valid DepositRequest request) {
        return ResponseEntity.ok(accountService.deposit(id, request));
    }

    @Operation(summary = "Withdraw funds",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Withdrawal successful"),
                   @ApiResponse(responseCode = "400", description = "Invalid amount"),
                   @ApiResponse(responseCode = "404", description = "Account not found"),
                   @ApiResponse(responseCode = "409", description = "Concurrent modification — retry"),
                   @ApiResponse(responseCode = "422", description = "Insufficient funds or account not active")
               })
    @PutMapping("/{id}/withdraw")
    ResponseEntity<AccountResponse> withdraw(
            @PathVariable Long id,
            @RequestBody @Valid WithdrawRequest request) {
        return ResponseEntity.ok(accountService.withdraw(id, request));
    }

    @Operation(summary = "Update account status (ACTIVE / SUSPENDED / CLOSED)")
    @PutMapping("/{id}/status")
    ResponseEntity<AccountResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid UpdateAccountStatusRequest request) {
        return ResponseEntity.ok(accountService.updateStatus(id, request));
    }

    @Operation(summary = "Close account (soft delete — sets status to CLOSED)",
               responses = {
                   @ApiResponse(responseCode = "204", description = "Account closed"),
                   @ApiResponse(responseCode = "404", description = "Account not found")
               })
    @DeleteMapping("/{id}")
    ResponseEntity<Void> closeAccount(@PathVariable Long id) {
        accountService.closeAccount(id);
        return ResponseEntity.noContent().build();
    }
}
