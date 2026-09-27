package com.logiplatform.repository;

import com.logiplatform.model.FinanceAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceAccountRepository extends JpaRepository<FinanceAccount, UUID> {
    List<FinanceAccount> findAllByTenantIdAndActiveTrueOrderByAccountCode(UUID tenantId);
    Optional<FinanceAccount> findByTenantIdAndAccountCode(UUID tenantId, String accountCode);
}
