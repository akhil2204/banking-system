package com.banking.transactionservice.client.fallback;

import com.banking.transactionservice.client.AccountClient;
import com.banking.transactionservice.dto.AccountResponse;
import com.banking.transactionservice.dto.DepositRequest;
import com.banking.transactionservice.dto.WithdrawRequest;
import com.banking.transactionservice.exception.AccountServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AccountClientFallbackFactory implements FallbackFactory<AccountClient> {

    @Override
    public AccountClient create(Throwable cause) {
        log.warn("account-service fallback triggered: {}", cause.getMessage());
        return new AccountClient() {

            @Override
            public AccountResponse getAccountById(Long id) {
                throw new AccountServiceUnavailableException(
                        "account-service is currently unavailable — please retry. Reason: " + cause.getMessage());
            }

            @Override
            public AccountResponse deposit(Long id, DepositRequest request) {
                throw new AccountServiceUnavailableException(
                        "account-service is currently unavailable — deposit could not be processed. Reason: " + cause.getMessage());
            }

            @Override
            public AccountResponse withdraw(Long id, WithdrawRequest request) {
                throw new AccountServiceUnavailableException(
                        "account-service is currently unavailable — withdrawal could not be processed. Reason: " + cause.getMessage());
            }
        };
    }
}
