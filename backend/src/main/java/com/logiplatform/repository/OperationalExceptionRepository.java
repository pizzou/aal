package com.logiplatform.repository;

import com.logiplatform.model.OperationalException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationalExceptionRepository extends JpaRepository<OperationalException, UUID> {
    List<OperationalException> findAllByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    List<OperationalException> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    // Limit operational lists to protect memory and response times as incident
    // history grows.
    List<OperationalException> findAllByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status,
            Pageable page);

    List<OperationalException> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable page);

    Optional<OperationalException> findByIdAndTenantId(UUID id, UUID tenantId);
}
