package com.logiplatform.repository;

import com.logiplatform.model.Trip;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {
    Page<Trip> findAllByTenantId(UUID tenantId, Pageable pageable);
    Optional<Trip> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Trip> findAllByTenantIdAndScheduledDepartureGreaterThanEqualAndScheduledDepartureLessThanOrderByScheduledDepartureAsc(
            UUID tenantId, Instant from, Instant to);
}

