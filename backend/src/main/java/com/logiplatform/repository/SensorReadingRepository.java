package com.logiplatform.repository;

import com.logiplatform.model.SensorReading;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SensorReadingRepository extends JpaRepository<SensorReading,UUID> {
    Page<SensorReading> findAllByTenantIdAndShipmentIdOrderByRecordedAtDesc(UUID tenantId,UUID shipmentId,Pageable pageable);
    Optional<SensorReading> findByTenantIdAndEventId(UUID tenantId,UUID eventId);
}
