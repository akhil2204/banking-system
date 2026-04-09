package com.banking.transactionservice.exception;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    ResponseEntity<ErrorResponse> handleTransactionNotFound(
            TransactionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccountNotAvailableException.class)
    ResponseEntity<ErrorResponse> handleAccountNotAvailable(
            AccountNotAvailableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(SelfTransferException.class)
    ResponseEntity<ErrorResponse> handleSelfTransfer(
            SelfTransferException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /*
     * Thrown by AccountClientFallbackFactory when the circuit for account-service
     * is OPEN. Normally caught inside TransactionServiceImpl (saves FAILED record),
     * but if it escapes (e.g. from resolveAccount before any transaction is saved),
     * return 503 here.
     */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    ResponseEntity<ErrorResponse> handleAccountServiceUnavailable(
            AccountServiceUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ErrorResponse> handleCircuitOpen(
            CallNotPermittedException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service circuit breaker is open — please retry shortly", request.getRequestURI());
    }

    /*
     * Unhandled FeignException: account-service is down or returned an
     * unexpected error. The transaction was likely saved as FAILED already
     * (handled inside the service). If this handler is reached, it means
     * the exception escaped the service's try/catch — surface as 503.
     */
    @ExceptionHandler(FeignException.class)
    ResponseEntity<ErrorResponse> handleFeign(
            FeignException ex, HttpServletRequest request) {
        if (ex.status() == 404) {
            return build(HttpStatus.NOT_FOUND, "Referenced account not found", request.getRequestURI());
        }
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable, please retry", request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        ));
    }
}
