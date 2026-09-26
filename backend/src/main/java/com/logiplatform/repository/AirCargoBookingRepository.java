package com.logiplatform.repository;

import com.logiplatform.model.AirCargoBooking;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AirCargoBookingRepository extends JpaRepository<AirCargoBooking,UUID>{
 Optional<AirCargoBooking> findByTenantIdAndId(UUID tenantId,UUID id);
 Optional<AirCargoBooking> findByTenantIdAndIdempotencyKey(UUID tenantId,String key);
 Optional<AirCargoBooking> findByTenantIdAndProviderReference(UUID tenantId,String reference);
 List<AirCargoBooking> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
