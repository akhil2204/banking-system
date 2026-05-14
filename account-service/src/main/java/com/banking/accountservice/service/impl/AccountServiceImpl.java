package com.banking.accountservice.service.impl;

import com.banking.accountservice.client.UserClient;
import com.banking.accountservice.dto.*;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.exception.*;
import com.banking.accountservice.messaging.AccountAuditEvent;
import com.banking.accountservice.messaging.AuditEventPublisher;
import com.banking.accountservice.messaging.KafkaTopics;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.service.AccountService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final UserClient userClient;
    private final AuditEventPublisher auditPublisher;

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

        AccountResponse saved = AccountResponse.from(accountRepository.save(account));
        auditPublisher.publish(KafkaTopics.AUDIT_ACCOUNTS, String.valueOf(saved.id()),
                new AccountAuditEvent(saved.id(), saved.accountNumber(), saved.userId(),
                        "CREATED", null, saved.status().name(), null, saved.balance(), LocalDateTime.now()));
        return saved;
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
        BigDecimal previousBalance = account.getBalance();
        account.setBalance(account.getBalance().add(request.amount()));
        AccountResponse saved = AccountResponse.from(accountRepository.save(account));
        auditPublisher.publish(KafkaTopics.AUDIT_ACCOUNTS, String.valueOf(id),
                new AccountAuditEvent(id, saved.accountNumber(), saved.userId(),
                        "DEPOSITED", null, null, previousBalance, saved.balance(), LocalDateTime.now()));
        return saved;
    }

    @Override
    @Transactional
    public AccountResponse withdraw(Long id, WithdrawRequest request) {
        Account account = requireActiveAccount(id);
        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    account.getAccountNumber(), request.amount(), account.getBalance());
        }
        BigDecimal previousBalance = account.getBalance();
        account.setBalance(account.getBalance().subtract(request.amount()));
        AccountResponse saved = AccountResponse.from(accountRepository.save(account));
        auditPublisher.publish(KafkaTopics.AUDIT_ACCOUNTS, String.valueOf(id),
                new AccountAuditEvent(id, saved.accountNumber(), saved.userId(),
                        "WITHDRAWN", null, null, previousBalance, saved.balance(), LocalDateTime.now()));
        return saved;
    }

    @Override
    @Transactional
    public AccountResponse updateStatus(Long id, UpdateAccountStatusRequest request) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        String previousStatus = account.getStatus().name();
        account.setStatus(request.status());
        AccountResponse saved = AccountResponse.from(accountRepository.save(account));
        auditPublisher.publish(KafkaTopics.AUDIT_ACCOUNTS, String.valueOf(id),
                new AccountAuditEvent(id, saved.accountNumber(), saved.userId(),
                        "STATUS_CHANGED", previousStatus, saved.status().name(), null, null, LocalDateTime.now()));
        return saved;
    }

    @Override
    @Transactional
    public void closeAccount(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        String previousStatus = account.getStatus().name();
        account.setStatus(AccountStatus.CLOSED);
        accountRepository.save(account);
        auditPublisher.publish(KafkaTopics.AUDIT_ACCOUNTS, String.valueOf(id),
                new AccountAuditEvent(id, account.getAccountNumber(), account.getUserId(),
                        "CLOSED", previousStatus, AccountStatus.CLOSED.name(), null, null, LocalDateTime.now()));
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
