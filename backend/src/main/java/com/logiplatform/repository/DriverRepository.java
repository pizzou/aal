package com.logiplatform.repository;

import com.logiplatform.model.Driver;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    List<Driver> findAllByTenantId(UUID tenantId);
    Optional<Driver> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id and d.tenantId = :tenantId")
    Optional<Driver> findLockedByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndLicenseNumber(UUID tenantId, String licenseNumber);
}
