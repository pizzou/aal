package com.logiplatform.repository;

import com.logiplatform.model.CargoPiece;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CargoPieceRepository extends JpaRepository<CargoPiece,UUID>{List<CargoPiece> findAllByTenantIdAndShipmentIdOrderByPieceNoAsc(UUID t,UUID s);}
