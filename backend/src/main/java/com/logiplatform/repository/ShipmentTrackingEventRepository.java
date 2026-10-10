package com.logiplatform.repository;

import com.logiplatform.model.ShipmentTrackingEvent;
import com.logiplatform.model.TrackingEventType;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentTrackingEventRepository extends JpaRepository<ShipmentTrackingEvent, UUID> {
    List<ShipmentTrackingEvent> findAllByTenantIdAndShipmentIdOrderByOccurredAtAsc(UUID tenantId, UUID shipmentId);

    Optional<ShipmentTrackingEvent> findFirstByTenantIdAndShipmentIdAndEventTypeInOrderByOccurredAtDesc(
            UUID tenantId, UUID shipmentId, Collection<TrackingEventType> eventTypes);
}
