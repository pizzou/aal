package com.logiplatform.repository;

import com.logiplatform.model.Vehicle;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findAllByTenantId(UUID tenantId);
    Optional<Vehicle> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);
}
