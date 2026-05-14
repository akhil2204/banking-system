package com.banking.auditservice.repository;

import com.banking.auditservice.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByEventType(String eventType, Pageable pageable);

    Page<AuditEvent> findAllByEventTypeAndEntityId(String eventType, Long entityId, Pageable pageable);

    Page<AuditEvent> findAllByUserId(Long userId, Pageable pageable);
}
