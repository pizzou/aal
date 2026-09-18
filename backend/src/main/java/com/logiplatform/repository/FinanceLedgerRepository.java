package com.logiplatform.repository;

import com.logiplatform.model.FinanceLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface FinanceLedgerRepository extends JpaRepository<FinanceLedgerEntry, UUID> {
    List<FinanceLedgerEntry> findAllByTenantIdOrderByPostedAtDesc(UUID tenantId);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from FinanceLedgerEntry e
            where e.tenantId = :tenantId
              and e.accountCode = :accountCode
              and e.entryType = :entryType
              and e.currency = :currency
            """)
    BigDecimal sumByAccount(@Param("tenantId") UUID tenantId,
            @Param("accountCode") String accountCode,
            @Param("entryType") String entryType,
            @Param("currency") String currency);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from FinanceLedgerEntry e
            where e.tenantId = :tenantId
              and e.sourceType = :sourceType
              and e.sourceId = :sourceId
              and e.entryType = :entryType
              and e.currency = :currency
            """)
    BigDecimal sumBySource(@Param("tenantId") UUID tenantId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") UUID sourceId,
            @Param("entryType") String entryType,
            @Param("currency") String currency);
}
