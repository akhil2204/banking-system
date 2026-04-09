package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // All transactions where the given account is either sender or receiver
    Page<Transaction> findAllBySourceAccountIdOrTargetAccountId(
            Long sourceAccountId, Long targetAccountId, Pageable pageable);

    Page<Transaction> findAllByStatus(TransactionStatus status, Pageable pageable);

    Page<Transaction> findAllByType(TransactionType type, Pageable pageable);

    Page<Transaction> findAllByStatusAndType(
            TransactionStatus status, TransactionType type, Pageable pageable);
}
