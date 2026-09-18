package com.logiplatform.repository;

import com.logiplatform.model.CustomsDeclaration;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CustomsDeclarationRepository extends JpaRepository<CustomsDeclaration,UUID>{List<CustomsDeclaration> findAllByTenantIdAndShipmentIdOrderBySubmittedAtDesc(UUID t,UUID s); Optional<CustomsDeclaration> findByTenantIdAndId(UUID t,UUID id);}
