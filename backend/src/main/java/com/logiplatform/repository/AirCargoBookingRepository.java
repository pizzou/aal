package com.logiplatform.repository;

import com.logiplatform.model.AirCargoBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AirCargoBookingRepository extends JpaRepository<AirCargoBooking, UUID> {

    Optional<AirCargoBooking> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<AirCargoBooking> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    Optional<AirCargoBooking> findByTenantIdAndProviderReference(UUID tenantId, String reference);

    List<AirCargoBooking> findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(
            UUID tenantId,
            UUID shipmentId);

    List<AirCargoBooking> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

   
    List<AirCargoBooking> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
