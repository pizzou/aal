package com.logiplatform.repository;

import com.logiplatform.model.VehicleGpsPosition;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VehicleGpsPositionRepository extends JpaRepository<VehicleGpsPosition, UUID> {

    Page<VehicleGpsPosition> findAllByTenantIdAndVehicleIdOrderByRecordedAtDesc(
            UUID tenantId,
            UUID vehicleId,
            Pageable pageable);

    Optional<VehicleGpsPosition> findFirstByTenantIdAndVehicleIdOrderByRecordedAtDesc(
            UUID tenantId,
            UUID vehicleId);
}