package com.logiplatform.repository;

import com.logiplatform.model.VehicleGpsPosition;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    List<VehicleGpsPosition> findAllByTenantIdAndVehicleIdInOrderByRecordedAtDesc(
            UUID tenantId,
            Collection<UUID> vehicleIds);
}