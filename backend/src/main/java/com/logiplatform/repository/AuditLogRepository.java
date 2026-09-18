package com.logiplatform.repository;

import com.logiplatform.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
