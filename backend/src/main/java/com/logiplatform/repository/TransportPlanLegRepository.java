package com.logiplatform.repository;

import com.logiplatform.model.TransportPlanLeg;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface TransportPlanLegRepository extends JpaRepository<TransportPlanLeg,UUID>{List<TransportPlanLeg> findAllByTenantIdAndShipmentIdOrderBySequenceNoAsc(UUID tenantId,UUID shipmentId);}
