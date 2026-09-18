package com.logiplatform.repository;

import com.logiplatform.model.TransportLeg;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface TransportLegRepository extends JpaRepository<TransportLeg,UUID>{List<TransportLeg> findAllByTenantIdAndShipmentIdOrderBySequenceNoAsc(UUID tenantId,UUID shipmentId);}
