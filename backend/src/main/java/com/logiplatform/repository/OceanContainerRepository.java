package com.logiplatform.repository;

import com.logiplatform.model.OceanContainer;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OceanContainerRepository extends JpaRepository<OceanContainer,UUID>{List<OceanContainer> findAllByTenantIdAndShipmentId(UUID tenantId,UUID shipmentId);}
