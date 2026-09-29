package com.logiplatform.repository;

import com.logiplatform.model.AirCargoFlight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AirCargoFlightRepository extends JpaRepository<AirCargoFlight, UUID> {

    List<AirCargoFlight> findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,
            String origin,
            String destination,
            Instant from,
            Instant to);

    List<AirCargoFlight> findAllByTenantIdAndOriginCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,
            String origin,
            Instant from,
            Instant to);

    /**
     * Searches flights whose origin airport is one of the supplied values.
     * Collection is intentional so callers can safely pass either List or Set.
     */
    List<AirCargoFlight> findAllByTenantIdAndOriginCodeInAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,
            Collection<String> origins,
            Instant from,
            Instant to);

    List<AirCargoFlight> findAllByTenantIdAndFlightNumberAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            UUID tenantId,
            String flightNumber,
            Instant from,
            Instant to);

    Optional<AirCargoFlight> findFirstByTenantIdAndProviderCodeAndProviderReference(
            UUID tenantId,
            String providerCode,
            String providerReference);

    Optional<AirCargoFlight> findFirstByTenantIdAndProviderCodeAndCarrierCodeAndFlightNumberAndDepartureTimeAndRateId(
            UUID tenantId,
            String providerCode,
            String carrierCode,
            String flightNumber,
            Instant departureTime,
            String rateId);

    Optional<AirCargoFlight> findByTenantIdAndId(
            UUID tenantId,
            UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select f from AirCargoFlight f
        where f.tenantId = :tenantId and f.id = :id
        """)
    Optional<AirCargoFlight> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id);
}
