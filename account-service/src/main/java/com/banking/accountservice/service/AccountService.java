package com.banking.accountservice.service;

import com.banking.accountservice.dto.*;
import com.banking.accountservice.entity.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request);

    Page<AccountResponse> getAllAccounts(AccountStatus status, Pageable pageable);

    AccountResponse getAccountById(Long id);

    AccountResponse getAccountByNumber(String accountNumber);

    Page<AccountResponse> getAccountsByUserId(Long userId, Pageable pageable);

    BalanceResponse getBalance(Long id);

    AccountResponse deposit(Long id, DepositRequest request);

    AccountResponse withdraw(Long id, WithdrawRequest request);

    AccountResponse updateStatus(Long id, UpdateAccountStatusRequest request);

    void closeAccount(Long id);
}
