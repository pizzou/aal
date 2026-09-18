package com.logiplatform.repository;

import com.logiplatform.model.SensorThreshold;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SensorThresholdRepository extends JpaRepository<SensorThreshold, UUID> {
    Optional<SensorThreshold> findByTenantIdAndShipmentId(UUID tenantId, UUID shipmentId);
}
