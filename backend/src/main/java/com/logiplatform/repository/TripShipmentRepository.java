package com.logiplatform.repository;

import com.logiplatform.model.TripShipment;
import com.logiplatform.model.TripShipmentId;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripShipmentRepository extends JpaRepository<TripShipment, TripShipmentId> {
    List<TripShipment> findAllByTenantIdAndId_TripId(UUID tenantId, UUID tripId);
    List<TripShipment> findAllByTenantId(UUID tenantId);
}
