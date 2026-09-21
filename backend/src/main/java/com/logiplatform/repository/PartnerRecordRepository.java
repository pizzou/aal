package com.logiplatform.repository;

import com.logiplatform.model.PartnerRecord;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerRecordRepository
        extends JpaRepository<PartnerRecord, UUID> {

    List<PartnerRecord> findAllByTenantIdOrderByCountryAscCompanyAsc(
            UUID tenantId);

    Optional<PartnerRecord> findByTenantIdAndPartnerId(
            UUID tenantId,
            String partnerId);

    Optional<PartnerRecord> findFirstByTenantIdAndCompanyIgnoreCaseAndCountryIgnoreCase(
            UUID tenantId,
            String company,
            String country);

}
