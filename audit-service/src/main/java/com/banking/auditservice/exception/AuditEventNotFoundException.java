package com.banking.auditservice.exception;

public class AuditEventNotFoundException extends RuntimeException {
    public AuditEventNotFoundException(Long id) {
        super("Audit event not found: " + id);
    }
}
