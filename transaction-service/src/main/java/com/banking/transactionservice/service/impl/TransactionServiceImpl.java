package com.banking.transactionservice.service.impl;

import com.banking.transactionservice.client.AccountClient;
import com.banking.transactionservice.config.RabbitMQConfig;
import com.banking.transactionservice.dto.*;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.exception.AccountNotAvailableException;
import com.banking.transactionservice.exception.AccountServiceUnavailableException;
import com.banking.transactionservice.exception.SelfTransferException;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.messaging.AuditEventPublisher;
import com.banking.transactionservice.messaging.KafkaTopics;
import com.banking.transactionservice.messaging.TransactionAuditEvent;
import com.banking.transactionservice.messaging.TransactionEvent;
import com.banking.transactionservice.messaging.TransactionEventPublisher;
import com.banking.transactionservice.repository.TransactionRepository;
import com.banking.transactionservice.service.TransactionService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;
    private final TransactionEventPublisher eventPublisher;
    private final AuditEventPublisher auditPublisher;

    @Override
    @Transactional
    public TransactionResponse processDebit(DebitCreditRequest request) {
        AccountResponse account = resolveAccount(request.accountId());

        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .type(TransactionType.DEBIT)
                        .sourceAccountId(request.accountId())
                        .amount(request.amount())
                        .description(request.description())
                        .build()
        );

        try {
            accountClient.withdraw(request.accountId(), new WithdrawRequest(request.amount()));
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(LocalDateTime.now());
            log.info("DEBIT completed: txn={} account={} amount={}", transaction.getId(), account.accountNumber(), request.amount());
            transactionRepository.save(transaction);
            publishNotification(account.userId(), transaction.getId(), "TRANSACTION_COMPLETED",
                    RabbitMQConfig.ROUTING_COMPLETED, "Debit Successful",
                    String.format("%s debited from account %s", request.amount(), account.accountNumber()));
        } catch (FeignException | AccountServiceUnavailableException e) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(extractReason(e));
            log.warn("DEBIT failed: txn={} reason={}", transaction.getId(), transaction.getFailureReason());
            transactionRepository.save(transaction);
            publishNotification(account.userId(), transaction.getId(), "TRANSACTION_FAILED",
                    RabbitMQConfig.ROUTING_FAILED, "Debit Failed",
                    String.format("Debit of %s from account %s failed", request.amount(), account.accountNumber()));
        }

        publishTransactionAudit(transaction, account.userId());
        return TransactionResponse.from(transaction);
    }

    @Override
    @Transactional
    public TransactionResponse processCredit(DebitCreditRequest request) {
        AccountResponse account = resolveAccount(request.accountId());

        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .type(TransactionType.CREDIT)
                        .targetAccountId(request.accountId())
                        .amount(request.amount())
                        .description(request.description())
                        .build()
        );

        try {
            accountClient.deposit(request.accountId(), new DepositRequest(request.amount()));
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(LocalDateTime.now());
            log.info("CREDIT completed: txn={} account={} amount={}", transaction.getId(), account.accountNumber(), request.amount());
            transactionRepository.save(transaction);
            publishNotification(account.userId(), transaction.getId(), "TRANSACTION_COMPLETED",
                    RabbitMQConfig.ROUTING_COMPLETED, "Credit Received",
                    String.format("%s credited to account %s", request.amount(), account.accountNumber()));
        } catch (FeignException | AccountServiceUnavailableException e) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(extractReason(e));
            log.warn("CREDIT failed: txn={} reason={}", transaction.getId(), transaction.getFailureReason());
            transactionRepository.save(transaction);
        }

        publishTransactionAudit(transaction, account.userId());
        return TransactionResponse.from(transaction);
    }

    @Override
    @Transactional
    public TransactionResponse processTransfer(TransferRequest request) {
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new SelfTransferException();
        }

        AccountResponse source = resolveAccount(request.sourceAccountId());
        AccountResponse target = resolveAccount(request.targetAccountId());

        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .type(TransactionType.TRANSFER)
                        .sourceAccountId(request.sourceAccountId())
                        .targetAccountId(request.targetAccountId())
                        .amount(request.amount())
                        .description(request.description())
                        .build()
        );

        // Step 1: withdraw from source
        try {
            accountClient.withdraw(request.sourceAccountId(), new WithdrawRequest(request.amount()));
        } catch (FeignException | AccountServiceUnavailableException e) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Withdrawal from source failed: " + extractReason(e));
            log.warn("TRANSFER step-1 failed: txn={} source={}", transaction.getId(), source.accountNumber());
            transactionRepository.save(transaction);
            publishNotification(source.userId(), transaction.getId(), "TRANSACTION_FAILED",
                    RabbitMQConfig.ROUTING_FAILED, "Transfer Failed",
                    String.format("Transfer of %s from account %s failed", request.amount(), source.accountNumber()));
            publishTransactionAudit(transaction, source.userId());
            return TransactionResponse.from(transaction);
        }

        // Step 2: deposit to target
        // IMPORTANT: if this fails, step-1 already debited the source — we MUST
        // attempt compensation (re-deposit to source) to avoid losing money.
        // AccountServiceUnavailableException is caught here for the same reason:
        // even if the circuit is open, the source was already debited and we
        // must attempt the refund before giving up.
        try {
            accountClient.deposit(request.targetAccountId(), new DepositRequest(request.amount()));
        } catch (FeignException | AccountServiceUnavailableException e) {
            log.warn("TRANSFER step-2 failed: txn={} target={} — attempting compensation", transaction.getId(), target.accountNumber());
            try {
                accountClient.deposit(request.sourceAccountId(), new DepositRequest(request.amount()));
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Deposit to target failed; source refunded. Reason: " + extractReason(e));
                log.info("TRANSFER compensation succeeded: txn={} source refunded", transaction.getId());
            } catch (FeignException | AccountServiceUnavailableException compensationEx) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason(
                        "CRITICAL: deposit to target failed AND compensation (source refund) failed. " +
                        "Source account " + source.accountNumber() + " was debited but target " +
                        target.accountNumber() + " was NOT credited. Manual intervention required.");
                log.error("TRANSFER compensation FAILED: txn={} MANUAL INTERVENTION REQUIRED", transaction.getId());
            }
            transactionRepository.save(transaction);
            publishNotification(source.userId(), transaction.getId(), "TRANSACTION_FAILED",
                    RabbitMQConfig.ROUTING_FAILED, "Transfer Failed",
                    String.format("Transfer of %s from account %s failed", request.amount(), source.accountNumber()));
            publishTransactionAudit(transaction, source.userId());
            return TransactionResponse.from(transaction);
        }

        // Both steps succeeded — notify both parties
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        log.info("TRANSFER completed: txn={} from={} to={} amount={}",
                transaction.getId(), source.accountNumber(), target.accountNumber(), request.amount());
        transactionRepository.save(transaction);

        publishNotification(source.userId(), transaction.getId(), "TRANSACTION_COMPLETED",
                RabbitMQConfig.ROUTING_COMPLETED, "Transfer Sent",
                String.format("%s transferred from %s to %s", request.amount(), source.accountNumber(), target.accountNumber()));
        publishNotification(target.userId(), transaction.getId(), "TRANSACTION_COMPLETED",
                RabbitMQConfig.ROUTING_COMPLETED, "Transfer Received",
                String.format("%s received in account %s from %s", request.amount(), target.accountNumber(), source.accountNumber()));

        publishTransactionAudit(transaction, source.userId());
        return TransactionResponse.from(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id) {
        return transactionRepository.findById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getAll(TransactionStatus status, TransactionType type, Pageable pageable) {
        if (status != null && type != null) {
            return transactionRepository.findAllByStatusAndType(status, type, pageable).map(TransactionResponse::from);
        }
        if (status != null) {
            return transactionRepository.findAllByStatus(status, pageable).map(TransactionResponse::from);
        }
        if (type != null) {
            return transactionRepository.findAllByType(type, pageable).map(TransactionResponse::from);
        }
        return transactionRepository.findAll(pageable).map(TransactionResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getByAccountId(Long accountId, Pageable pageable) {
        return transactionRepository
                .findAllBySourceAccountIdOrTargetAccountId(accountId, accountId, pageable)
                .map(TransactionResponse::from);
    }

    // ------------------------------------------------------------------ //

    private AccountResponse resolveAccount(Long accountId) {
        try {
            AccountResponse account = accountClient.getAccountById(accountId);
            if (!"ACTIVE".equals(account.status())) {
                throw new AccountNotAvailableException(
                        "Account " + account.accountNumber() + " is not active (status: " + account.status() + ")");
            }
            return account;
        } catch (FeignException.NotFound e) {
            throw new AccountNotAvailableException("Account " + accountId + " not found");
        }
    }

    /*
     * Publish an immutable audit record to Kafka after every terminal state.
     * key = transactionId ensures ordering within the partition for this entity.
     * userId is the account owner — may be null if account resolution failed.
     */
    private void publishTransactionAudit(Transaction transaction, Long userId) {
        TransactionAuditEvent event = new TransactionAuditEvent(
                transaction.getId(),
                transaction.getSourceAccountId(),
                transaction.getTargetAccountId(),
                userId,
                transaction.getType().name(),
                transaction.getAmount(),
                transaction.getStatus().name(),
                transaction.getDescription(),
                transaction.getFailureReason(),
                transaction.getCompletedAt() != null ? transaction.getCompletedAt() : transaction.getCreatedAt()
        );
        auditPublisher.publish(KafkaTopics.AUDIT_TRANSACTIONS, String.valueOf(transaction.getId()), event);
    }

    /*
     * Publish a notification event to RabbitMQ.
     * Fire-and-forget: the publisher catches all exceptions internally so
     * a broker outage never rolls back or delays the transaction response.
     * notification-service consumes the event asynchronously in its own time.
     */
    private void publishNotification(Long userId, Long transactionId,
                                     String type, String routingKey,
                                     String title, String message) {
        if (userId == null) return;
        eventPublisher.publish(
                new TransactionEvent(transactionId, userId, type, "IN_APP", title, message),
                routingKey
        );
    }

    /*
     * Accepts Exception because catch blocks now use multi-catch:
     *   catch (FeignException | AccountServiceUnavailableException e)
     * In multi-catch, the variable type is the common ancestor (Exception).
     * We inspect the actual type to format a useful failure reason.
     */
    private String extractReason(Exception e) {
        if (e instanceof FeignException fe) {
            String msg = fe.getMessage();
            if (msg == null) return "Unknown error from account-service";
            return msg.length() > 500 ? msg.substring(0, 500) : msg;
        }
        // AccountServiceUnavailableException — circuit was open, no HTTP call made
        String msg = e.getMessage();
        return msg != null ? msg : "account-service circuit breaker is open";
    }
}
