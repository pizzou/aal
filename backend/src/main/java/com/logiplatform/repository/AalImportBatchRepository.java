package com.logiplatform.repository;

import com.logiplatform.model.AalImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AalImportBatchRepository extends JpaRepository<AalImportBatch, UUID> {
    Optional<AalImportBatch> findByTenantIdAndSourceSha256(UUID tenantId, String sourceSha256);
}
