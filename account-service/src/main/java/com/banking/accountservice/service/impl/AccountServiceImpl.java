package com.banking.accountservice.service.impl;

import com.banking.accountservice.client.UserClient;
import com.banking.accountservice.dto.*;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.exception.*;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.service.AccountService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final UserClient userClient;

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        /*
         * Validate user via Feign before creating the account.
         * FeignException.NotFound propagates to GlobalExceptionHandler → 404.
         * Any other FeignException (service down, timeout) → 503.
         */
        UserResponse user;
        try {
            user = userClient.getUserById(request.userId());
        } catch (FeignException.NotFound e) {
            throw new AccountNotFoundException("User " + request.userId() + " not found");
        }

        if (!"ACTIVE".equals(user.status())) {
            throw new UserNotActiveException(request.userId());
        }

        Account account = Account.builder()
                .userId(request.userId())
                .accountNumber(generateAccountNumber())
                .type(request.type())
                .build();

        return AccountResponse.from(accountRepository.save(account));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountResponse> getAllAccounts(AccountStatus status, Pageable pageable) {
        if (status != null) {
            return accountRepository.findAllByStatus(status, pageable).map(AccountResponse::from);
        }
        return accountRepository.findAll(pageable).map(AccountResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id) {
        return accountRepository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccountByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountResponse> getAccountsByUserId(Long userId, Pageable pageable) {
        return accountRepository.findAllByUserId(userId, pageable).map(AccountResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        return new BalanceResponse(account.getId(), account.getAccountNumber(), account.getBalance());
    }

    @Override
    @Transactional
    public AccountResponse deposit(Long id, DepositRequest request) {
        Account account = requireActiveAccount(id);
        /*
         * BigDecimal.add() returns a new instance — it does not mutate.
         * Always assign the result back: balance = balance.add(amount).
         */
        account.setBalance(account.getBalance().add(request.amount()));
        return AccountResponse.from(accountRepository.save(account));
    }

    @Override
    @Transactional
    public AccountResponse withdraw(Long id, WithdrawRequest request) {
        Account account = requireActiveAccount(id);
        /*
         * compareTo is the correct way to compare BigDecimal values.
         * equals() also checks scale: 1.0.equals(1.00) is false.
         * compareTo() only compares numeric value: 1.0.compareTo(1.00) == 0.
         */
        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    account.getAccountNumber(), request.amount(), account.getBalance());
        }
        account.setBalance(account.getBalance().subtract(request.amount()));
        return AccountResponse.from(accountRepository.save(account));
    }

    @Override
    @Transactional
    public AccountResponse updateStatus(Long id, UpdateAccountStatusRequest request) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.setStatus(request.status());
        return AccountResponse.from(accountRepository.save(account));
    }

    @Override
    @Transactional
    public void closeAccount(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.setStatus(AccountStatus.CLOSED);
        accountRepository.save(account);
    }

    // ------------------------------------------------------------------ //

    private Account requireActiveAccount(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.getAccountNumber(), account.getStatus());
        }
        return account;
    }

    /*
     * UUID gives 122 bits of randomness. Taking the first 12 hex chars still
     * leaves 48 bits = ~281 trillion combinations. Collision probability at
     * 1 million accounts: ~1 in 281 billion. Acceptable without a DB check.
     */
    private String generateAccountNumber() {
        return "ACC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
