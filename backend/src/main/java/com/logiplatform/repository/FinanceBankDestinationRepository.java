package com.logiplatform.repository;

import com.logiplatform.model.FinanceBankDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FinanceBankDestinationRepository extends JpaRepository<FinanceBankDestination, UUID> {
    List<FinanceBankDestination> findAllByTenantIdOrderByName(UUID tenantId);
    Optional<FinanceBankDestination> findByTenantIdAndId(UUID tenantId, UUID id);
    Optional<FinanceBankDestination> findByTenantIdAndCode(UUID tenantId, String code);
}
