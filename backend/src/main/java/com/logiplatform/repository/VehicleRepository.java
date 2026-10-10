package com.logiplatform.repository;

import com.logiplatform.model.Vehicle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findAllByTenantId(UUID tenantId);
    Optional<Vehicle> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id and v.tenantId = :tenantId")
    Optional<Vehicle> findLockedByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);
}
