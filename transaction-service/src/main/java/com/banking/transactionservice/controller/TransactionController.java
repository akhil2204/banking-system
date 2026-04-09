package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.DebitCreditRequest;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.service.TransactionService;
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

@Tag(name = "Transactions", description = "Debit, credit, and transfer operations")
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "Debit an account (money out)",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Transaction created (check status field — may be FAILED)"),
                   @ApiResponse(responseCode = "400", description = "Validation failed"),
                   @ApiResponse(responseCode = "422", description = "Account not active or not found")
               })
    @PostMapping("/debit")
    ResponseEntity<TransactionResponse> debit(@RequestBody @Valid DebitCreditRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.processDebit(request));
    }

    @Operation(summary = "Credit an account (money in)",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Transaction created (check status field — may be FAILED)"),
                   @ApiResponse(responseCode = "422", description = "Account not active or not found")
               })
    @PostMapping("/credit")
    ResponseEntity<TransactionResponse> credit(@RequestBody @Valid DebitCreditRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.processCredit(request));
    }

    @Operation(summary = "Transfer between two accounts",
               description = "Atomic two-leg transfer with compensating rollback if the deposit leg fails. " +
                             "Always returns 201 — check the 'status' field (COMPLETED or FAILED) and 'failureReason'.",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Transfer processed (COMPLETED or FAILED)"),
                   @ApiResponse(responseCode = "400", description = "Same source and target, or validation failed"),
                   @ApiResponse(responseCode = "422", description = "An account is not active or not found")
               })
    @PostMapping("/transfer")
    ResponseEntity<TransactionResponse> transfer(@RequestBody @Valid TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.processTransfer(request));
    }

    @Operation(summary = "List all transactions (paginated)",
               description = "Optional filters: ?status=PENDING|COMPLETED|FAILED and ?type=DEBIT|CREDIT|TRANSFER")
    @GetMapping
    ResponseEntity<Page<TransactionResponse>> getAll(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(transactionService.getAll(status, type, pageable));
    }

    @Operation(summary = "Get transaction by ID")
    @GetMapping("/{id}")
    ResponseEntity<TransactionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(transactionService.getById(id));
    }

    @Operation(summary = "Get all transactions for an account (as sender or receiver)")
    @GetMapping("/account/{accountId}")
    ResponseEntity<Page<TransactionResponse>> getByAccountId(
            @PathVariable Long accountId,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(transactionService.getByAccountId(accountId, pageable));
    }
}
