package com.logiplatform.repository;

import com.logiplatform.model.OceanBooking;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OceanBookingRepository extends JpaRepository<OceanBooking,UUID>{List<OceanBooking> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);}
