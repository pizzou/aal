package com.logiplatform.repository;

import com.logiplatform.model.CommercialInvoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CommercialInvoiceRepository extends JpaRepository<CommercialInvoice, UUID> {
    List<CommercialInvoice> findAllByTenantIdOrderByIssueDateDesc(UUID t);

    Optional<CommercialInvoice> findByTenantIdAndInvoiceNo(UUID t, String n);

    Optional<CommercialInvoice> findByTenantIdAndShipmentId(UUID t, UUID shipmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from CommercialInvoice i
            where i.tenantId = :tenantId and i.id = :id
            """)
    Optional<CommercialInvoice> findByTenantIdAndIdForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
