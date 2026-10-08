package com.logiplatform.repository;

import com.logiplatform.model.FinanceIncomeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FinanceIncomeSourceRepository extends JpaRepository<FinanceIncomeSource, UUID> {
    List<FinanceIncomeSource> findAllByTenantIdOrderByName(UUID tenantId);
    Optional<FinanceIncomeSource> findByTenantIdAndId(UUID tenantId, UUID id);
    Optional<FinanceIncomeSource> findByTenantIdAndCode(UUID tenantId, String code);
}
