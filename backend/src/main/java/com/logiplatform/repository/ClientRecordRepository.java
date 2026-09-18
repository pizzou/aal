package com.logiplatform.repository;

import com.logiplatform.model.ClientRecord;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRecordRepository
        extends JpaRepository<ClientRecord, UUID> {

    List<ClientRecord> findAllByTenantIdOrderByClientCompanyAsc(
            UUID tenantId);

    Optional<ClientRecord> findByTenantIdAndClientId(
            UUID tenantId,
            String clientId);

}
