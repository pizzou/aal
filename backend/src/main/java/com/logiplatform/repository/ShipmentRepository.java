package com.logiplatform.repository;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.TransportMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<Shipment> findAllByTenantId(UUID tenantId, Pageable pageable);

    @Query("""
            SELECT s
              FROM Shipment s
             WHERE s.tenantId = :tenantId
               AND (
                    :q IS NULL OR :q = ''
                    OR LOWER(COALESCE(s.referenceCode,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.clientName,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.contact,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.commodity,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.airlineUsed,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.carrierName,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.invoiceNo,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.originCityPort,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destinationCityPort,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.originAddress,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destinationAddress,'')) LIKE LOWER(CONCAT('%', :q, '%'))
               )
               AND (:mode IS NULL OR s.transportMode = :mode)
               AND (:status IS NULL OR s.status = :status)
            """)
    Page<Shipment> search(
            @Param("tenantId") UUID tenantId,
            @Param("q") String q,
            @Param("mode") TransportMode mode,
            @Param("status") ShipmentStatus status,
            Pageable pageable);
    List<Shipment> findAllByTenantIdAndWeightKgIsNotNullAndStatus(UUID tenantId, ShipmentStatus status);
    boolean existsByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    Optional<Shipment> findByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    List<Shipment> findAllByTenantIdAndEtdGreaterThanEqualAndEtdLessThanOrderByEtdAsc(UUID tenantId, Instant from, Instant to);
    List<Shipment> findAllByTenantIdAndNextActionDateOrderByNextActionDateAsc(UUID tenantId, LocalDate date);
    List<Shipment> findAllByTenantIdAndDateOpenedGreaterThanEqualAndDateOpenedLessThan(
            UUID tenantId, LocalDate fromInclusive, LocalDate toExclusive);
}
