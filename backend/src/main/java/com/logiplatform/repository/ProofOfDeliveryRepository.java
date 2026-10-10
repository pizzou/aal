package com.logiplatform.repository;

import com.logiplatform.model.ProofOfDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProofOfDeliveryRepository extends JpaRepository<ProofOfDelivery, UUID> {
    @Query("""
            select case when count(p) > 0 then true else false end
              from ProofOfDelivery p
             where p.tenantId = :tenantId and p.shipmentId = :shipmentId
               and (p.failureReason is null or trim(p.failureReason) = '')
            """)
    boolean hasSuccessfulDeliveryEvidence(
            @Param("tenantId") UUID tenantId,
            @Param("shipmentId") UUID shipmentId);

    @Query("""
            select p from ProofOfDelivery p
             where p.tenantId = :tenantId and p.shipmentId = :shipmentId
               and (p.failureReason is null or trim(p.failureReason) = '')
             order by p.deliveredAt desc
            """)
    List<ProofOfDelivery> findSuccessfulByTenantAndShipment(
            @Param("tenantId") UUID tenantId,
            @Param("shipmentId") UUID shipmentId);

    Optional<ProofOfDelivery> findFirstByTenantIdAndShipmentIdOrderByDeliveredAtDesc(UUID tenantId, UUID shipmentId);

    List<ProofOfDelivery> findAllByTenantIdOrderByDeliveredAtDesc(UUID tenantId);
}
