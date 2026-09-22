package com.logiplatform.repository;

import com.logiplatform.model.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateCardRepository extends JpaRepository<RateCard, UUID> {
    List<RateCard> findAllByTenantId(UUID tenantId);

    @Query("""
        select r from RateCard r
        where r.tenantId = :tenantId
          and upper(r.transportMode) = upper(:mode)
          and r.active = true
          and r.validFrom <= :asOf
          and (r.validUntil is null or r.validUntil >= :asOf)
        order by r.validFrom desc
        """)
    List<RateCard> findEffective(
            @Param("tenantId") UUID tenantId,
            @Param("mode") String mode,
            @Param("asOf") LocalDate asOf);

    default Optional<RateCard> findFirstEffective(UUID tenantId, String mode, LocalDate asOf) {
        return findEffective(tenantId, mode, asOf).stream().findFirst();
    }
}
