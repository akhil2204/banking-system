package com.banking.auditservice.service;

import com.banking.auditservice.dto.AuditEventResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditService {

    AuditEventResponse getById(Long id);

    Page<AuditEventResponse> getAll(String eventType, Pageable pageable);

    Page<AuditEventResponse> getByEntity(String eventType, Long entityId, Pageable pageable);

    Page<AuditEventResponse> getByUser(Long userId, Pageable pageable);

    AuditEventResponse save(String eventType, Long entityId, Long userId,
                            String action, String payload, java.time.LocalDateTime occurredAt);
}
