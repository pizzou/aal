package com.logiplatform.repository;

import com.logiplatform.model.TripShipment;
import com.logiplatform.model.TripShipmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TripShipmentRepository extends JpaRepository<TripShipment, TripShipmentId> {
    List<TripShipment> findAllByTenantIdAndId_TripId(UUID tenantId, UUID tripId);
    List<TripShipment> findAllByTenantId(UUID tenantId);
    boolean existsByTenantIdAndId_TripIdAndId_ShipmentId(UUID tenantId, UUID tripId, UUID shipmentId);

    /** A shipment can be assigned to only one planned/active trip in this single-leg dispatch model. */
    @Query(value = """
            select exists (
                select 1
                  from trip_shipments ts
                  join trips t on t.id = ts.trip_id
                 where ts.tenant_id = :tenantId
                   and t.tenant_id = :tenantId
                   and ts.shipment_id = :shipmentId
                   and t.status in ('PLANNED', 'IN_PROGRESS')
            )
            """, nativeQuery = true)
    boolean hasPlannedOrActiveTripAssignment(
            @Param("tenantId") UUID tenantId,
            @Param("shipmentId") UUID shipmentId);
}
