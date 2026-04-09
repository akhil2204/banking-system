package com.banking.transactionservice.client;

import com.banking.transactionservice.client.fallback.AccountClientFallbackFactory;
import com.banking.transactionservice.dto.AccountResponse;
import com.banking.transactionservice.dto.DepositRequest;
import com.banking.transactionservice.dto.WithdrawRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/*
 * These paths (/accounts/**) are account-service's internal paths — NOT
 * the gateway paths (/v1/accounts/**). Feign resolves "account-service"
 * via Eureka to a direct IP:port and calls the service without going
 * through the gateway. Never route inter-service Feign calls through
 * the gateway — it adds an unnecessary network hop and couples services
 * to the gateway's versioning strategy.
 */
@FeignClient(name = "account-service", fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {

    @GetMapping("/accounts/{id}")
    AccountResponse getAccountById(@PathVariable Long id);

    @PutMapping("/accounts/{id}/deposit")
    AccountResponse deposit(@PathVariable Long id, @RequestBody DepositRequest request);

    @PutMapping("/accounts/{id}/withdraw")
    AccountResponse withdraw(@PathVariable Long id, @RequestBody WithdrawRequest request);
}
