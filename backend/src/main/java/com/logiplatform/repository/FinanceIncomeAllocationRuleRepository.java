package com.logiplatform.repository;

import com.logiplatform.model.FinanceIncomeAllocationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FinanceIncomeAllocationRuleRepository extends JpaRepository<FinanceIncomeAllocationRule, UUID> {
    List<FinanceIncomeAllocationRule> findAllByTenantIdOrderByIncomeSourceIdBankDestinationId(UUID tenantId);
    List<FinanceIncomeAllocationRule> findAllByTenantIdAndIncomeSourceIdAndActiveTrue(UUID tenantId, UUID incomeSourceId);
    Optional<FinanceIncomeAllocationRule> findByTenantIdAndId(UUID tenantId, UUID id);
}
