package com.banking.auditservice.messaging;

public final class KafkaTopics {
    public static final String AUDIT_TRANSACTIONS = "banking.audit.transactions";
    public static final String AUDIT_ACCOUNTS     = "banking.audit.accounts";

    private KafkaTopics() {}
}
