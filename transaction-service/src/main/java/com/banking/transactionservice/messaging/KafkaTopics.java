package com.banking.transactionservice.messaging;

/**
 * Kafka topic name constants shared across the messaging layer.
 * All services must agree on these exact strings — they are the contract.
 */
public final class KafkaTopics {
    public static final String AUDIT_TRANSACTIONS = "banking.audit.transactions";
    public static final String AUDIT_ACCOUNTS     = "banking.audit.accounts";

    private KafkaTopics() {}
}
