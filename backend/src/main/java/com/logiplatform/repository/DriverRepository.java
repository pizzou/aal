package com.logiplatform.repository;

import com.logiplatform.model.Driver;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    List<Driver> findAllByTenantId(UUID tenantId);
    Optional<Driver> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndLicenseNumber(UUID tenantId, String licenseNumber);
}
