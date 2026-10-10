package com.logiplatform.repository;

import com.logiplatform.model.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {
    Page<Trip> findAllByTenantId(UUID tenantId, Pageable pageable);
    Optional<Trip> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id and t.tenantId = :tenantId")
    Optional<Trip> findLockedByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    List<Trip> findAllByTenantIdAndScheduledDepartureGreaterThanEqualAndScheduledDepartureLessThanOrderByScheduledDepartureAsc(
            UUID tenantId, Instant from, Instant to);
}
