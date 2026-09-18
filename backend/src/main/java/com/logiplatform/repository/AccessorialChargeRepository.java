package com.logiplatform.repository;

import com.logiplatform.model.AccessorialCharge;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccessorialChargeRepository extends JpaRepository<AccessorialCharge, UUID> {
    List<AccessorialCharge> findAllByTenantId(UUID tenantId);
    List<AccessorialCharge> findAllByTenantIdAndCodeIn(UUID tenantId, List<String> codes);
}
