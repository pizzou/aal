package com.logiplatform.repository;

import com.logiplatform.model.OceanVoyage;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OceanVoyageRepository extends JpaRepository<OceanVoyage,UUID>{List<OceanVoyage> findAllByTenantIdOrderByEtdAsc(UUID tenantId);}
