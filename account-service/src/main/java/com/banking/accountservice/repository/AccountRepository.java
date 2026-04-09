package com.banking.accountservice.repository;

import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    Page<Account> findAllByUserId(Long userId, Pageable pageable);

    Page<Account> findAllByStatus(AccountStatus status, Pageable pageable);

    boolean existsByAccountNumber(String accountNumber);
}
