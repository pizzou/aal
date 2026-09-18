package com.logiplatform.repository;

import com.logiplatform.model.AirCargoFlight;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AirCargoFlightRepository extends JpaRepository<AirCargoFlight, UUID> {
    List<AirCargoFlight> findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,String origin,String destination,Instant from,Instant to);
    List<AirCargoFlight> findAllByTenantIdAndOriginCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,String origin,Instant from,Instant to);
    Optional<AirCargoFlight> findByTenantIdAndId(UUID tenantId,UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select f from AirCargoFlight f
        where f.tenantId = :tenantId and f.id = :id
        """)
    Optional<AirCargoFlight> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId,@Param("id") UUID id);
}
