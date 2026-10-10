package com.logiplatform.repository;

import com.logiplatform.model.DispatchStop;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchStopRepository extends JpaRepository<DispatchStop, UUID> {
    List<DispatchStop> findAllByTenantIdAndTripIdOrderBySequenceNoAsc(UUID tenantId, UUID tripId);

    Optional<DispatchStop> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DispatchStop s where s.id = :id and s.tenantId = :tenantId")
    Optional<DispatchStop> findLockedByIdAndTenantId(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndTripIdAndSequenceNo(UUID tenantId, UUID tripId, int sequenceNo);

    boolean existsByTenantIdAndTripIdAndShipmentIdAndAddressIgnoreCaseAndStatusIn(
            UUID tenantId, UUID tripId, UUID shipmentId, String address, Collection<String> statuses);
}
