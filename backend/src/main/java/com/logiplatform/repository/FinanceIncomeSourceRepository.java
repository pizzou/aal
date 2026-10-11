package com.logiplatform.repository;

import com.logiplatform.model.FinanceIncomeSource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface FinanceIncomeSourceRepository extends JpaRepository<FinanceIncomeSource, UUID> {
    List<FinanceIncomeSource> findAllByTenantIdOrderByName(UUID tenantId);
    Optional<FinanceIncomeSource> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Serializes allocation-rule changes and payment snapshots for one source. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FinanceIncomeSource s where s.tenantId = :tenantId and s.id = :id")
    Optional<FinanceIncomeSource> findLockedByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);
    Optional<FinanceIncomeSource> findByTenantIdAndCode(UUID tenantId, String code);
}
