package com.logiplatform.repository;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;

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

    @Query(value = """
            SELECT *
              FROM shipments s
             WHERE s.tenant_id = :tenantId
               AND (
                    :q = ''
                    OR LOWER(COALESCE(s.reference_code,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.client_name,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.contact,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.commodity,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.airline_used,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.carrier_name,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.invoice_no,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.origin_city_port,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destination_city_port,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.origin_address,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destination_address,'')) LIKE LOWER(CONCAT('%', :q, '%'))
               )
               AND (:mode = '' OR UPPER(s.transport_mode) = UPPER(:mode))
               AND (:status = '' OR UPPER(s.status) = UPPER(:status))
            """,
            countQuery = """
            SELECT COUNT(*)
              FROM shipments s
             WHERE s.tenant_id = :tenantId
               AND (
                    :q = ''
                    OR LOWER(COALESCE(s.reference_code,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.client_name,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.contact,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.commodity,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.airline_used,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.carrier_name,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.invoice_no,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.origin_city_port,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destination_city_port,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.origin_address,'')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.destination_address,'')) LIKE LOWER(CONCAT('%', :q, '%'))
               )
               AND (:mode = '' OR UPPER(s.transport_mode) = UPPER(:mode))
               AND (:status = '' OR UPPER(s.status) = UPPER(:status))
            """,
            nativeQuery = true)
    Page<Shipment> search(
            @Param("tenantId") UUID tenantId,
            @Param("q") String q,
            @Param("mode") String mode,
            @Param("status") String status,
            Pageable pageable);
    List<Shipment> findAllByTenantIdAndWeightKgIsNotNullAndStatus(UUID tenantId, ShipmentStatus status);
    boolean existsByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    Optional<Shipment> findByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    List<Shipment> findAllByTenantIdAndEtdGreaterThanEqualAndEtdLessThanOrderByEtdAsc(UUID tenantId, Instant from, Instant to);
    List<Shipment> findAllByTenantIdAndNextActionDateOrderByNextActionDateAsc(UUID tenantId, LocalDate date);
    List<Shipment> findAllByTenantIdAndDateOpenedGreaterThanEqualAndDateOpenedLessThan(
            UUID tenantId, LocalDate fromInclusive, LocalDate toExclusive);
}
