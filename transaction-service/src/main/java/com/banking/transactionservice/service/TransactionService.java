package com.banking.transactionservice.service;

import com.banking.transactionservice.dto.DebitCreditRequest;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TransactionService {

    TransactionResponse processDebit(DebitCreditRequest request);

    TransactionResponse processCredit(DebitCreditRequest request);

    TransactionResponse processTransfer(TransferRequest request);

    TransactionResponse getById(Long id);

    Page<TransactionResponse> getAll(TransactionStatus status, TransactionType type, Pageable pageable);

    Page<TransactionResponse> getByAccountId(Long accountId, Pageable pageable);
}
