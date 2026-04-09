package com.banking.transactionservice.entity;

public enum TransactionStatus {
    /*
     * PENDING: written to DB before any Feign call is made.
     * If the process crashes mid-execution, this record survives and
     * is visible to ops — without PENDING, a crash between "withdraw succeeded"
     * and "deposit succeeded" would be completely invisible.
     */
    PENDING,
    COMPLETED,
    FAILED
}
