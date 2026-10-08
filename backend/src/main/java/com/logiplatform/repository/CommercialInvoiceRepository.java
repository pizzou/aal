package com.logiplatform.repository;

import com.logiplatform.model.CommercialInvoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommercialInvoiceRepository
        extends JpaRepository<CommercialInvoice, UUID> {


    List<CommercialInvoice> findAllByTenantIdOrderByIssueDateDesc(
            UUID tenantId);

    
    Optional<CommercialInvoice> findByTenantIdAndInvoiceNo(
            UUID tenantId,
            String invoiceNo);

    
    Optional<CommercialInvoice> findByTenantIdAndShipmentId(
            UUID tenantId,
            UUID shipmentId);

    Optional<CommercialInvoice> findByTenantIdAndId(
            UUID tenantId,
            UUID id);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i
            from CommercialInvoice i
            where i.tenantId = :tenantId
              and i.id = :id
            """)
    Optional<CommercialInvoice> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id);

   
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update CommercialInvoice i
               set i.lifecycleStatus = :status
             where i.tenantId = :tenantId
               and i.id = :id
            """)
    int updateLifecycleStatus(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("status") String status);

   
    @Query(value = """
            INSERT INTO commercial_invoice_lifecycle_history
                (
                    tenant_id,
                    invoice_id,
                    previous_status,
                    new_status,
                    reason,
                    changed_by,
                    changed_at
                )
            VALUES
                (
                    :tenantId,
                    :invoiceId,
                    :previousStatus,
                    :newStatus,
                    :reason,
                    :changedBy,
                    now()
                )
            """, nativeQuery = true)
    @Modifying
    @Transactional
    int insertLifecycleHistory(
            @Param("tenantId") UUID tenantId,
            @Param("invoiceId") UUID invoiceId,
            @Param("previousStatus") String previousStatus,
            @Param("newStatus") String newStatus,
            @Param("reason") String reason,
            @Param("changedBy") UUID changedBy);
}

