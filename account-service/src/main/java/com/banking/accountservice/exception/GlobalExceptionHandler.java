package com.banking.accountservice.exception;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ErrorResponse> handleAccountNotFound(
            AccountNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorResponse> handleInsufficientFunds(
            InsufficientFundsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccountNotActiveException.class)
    ResponseEntity<ErrorResponse> handleAccountNotActive(
            AccountNotActiveException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UserNotActiveException.class)
    ResponseEntity<ErrorResponse> handleUserNotActive(
            UserNotActiveException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI());
    }

    /*
     * Thrown by UserClientFallbackFactory when the Resilience4j circuit breaker
     * for user-service is OPEN. The fallback converts CallNotPermittedException
     * into this domain exception so the handler can return a clear 503.
     */
    @ExceptionHandler(UserServiceUnavailableException.class)
    ResponseEntity<ErrorResponse> handleUserServiceUnavailable(
            UserServiceUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request.getRequestURI());
    }

    /*
     * Defensive handler for CallNotPermittedException — reached only if a
     * FeignClient has no fallbackFactory and the circuit is OPEN. With
     * fallbackFactories wired on all clients this should never fire, but it
     * prevents a raw 500 if a new client is added without a fallback.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ErrorResponse> handleCircuitOpen(
            CallNotPermittedException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service circuit breaker is open — please retry shortly", request.getRequestURI());
    }

    /*
     * FeignException is thrown when a downstream HTTP call fails.
     * We branch on the HTTP status code returned by the downstream service:
     *   404 → the referenced resource (e.g. user) does not exist
     *   anything else → treat as upstream unavailable (503)
     *
     * This keeps downstream errors from leaking a raw 500 to the caller.
     */
    @ExceptionHandler(FeignException.class)
    ResponseEntity<ErrorResponse> handleFeignException(
            FeignException ex, HttpServletRequest request) {
        if (ex.status() == 404) {
            return build(HttpStatus.NOT_FOUND, "Referenced resource not found in upstream service", request.getRequestURI());
        }
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable, please retry", request.getRequestURI());
    }

    /*
     * ObjectOptimisticLockingFailureException: two concurrent requests tried
     * to update the same account row. The @Version check caught the conflict.
     * 409 Conflict signals to the client that it should retry the operation.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Account was modified by a concurrent request. Please retry.", request.getRequestURI());
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
