package com.logiplatform.service;

import com.logiplatform.model.CargoPiece;
import com.logiplatform.repository.CargoPieceRepository;


import com.logiplatform.tenancy.TenantContext; import org.springframework.stereotype.Service; import java.math.*; import java.util.*;
@Service public class AirLoadPlanningService {
 private final CargoPieceRepository pieces; public AirLoadPlanningService(CargoPieceRepository p){pieces=p;}
 public Plan plan(UUID shipmentId,BigDecimal maxWeightKg,BigDecimal maxVolumeM3){
  if(maxWeightKg==null||maxWeightKg.signum()<=0)throw new IllegalArgumentException("maxWeightKg must be positive");
  if(maxVolumeM3==null||maxVolumeM3.signum()<=0)throw new IllegalArgumentException("maxVolumeM3 must be positive");List<CargoPiece> all=pieces.findAllByTenantIdAndShipmentIdOrderByPieceNoAsc(TenantContext.getTenantId(),shipmentId);List<CargoPiece> sorted=all.stream().sorted(Comparator.comparing(CargoPiece::volumeM3).reversed().thenComparing(CargoPiece::getWeightKg,Comparator.reverseOrder())).toList();List<UUID> loaded=new ArrayList<>(),excluded=new ArrayList<>();BigDecimal w=BigDecimal.ZERO,v=BigDecimal.ZERO;for(CargoPiece p:sorted){BigDecimal nw=w.add(p.getWeightKg()),nv=v.add(p.volumeM3());if(nw.compareTo(maxWeightKg)<=0&&nv.compareTo(maxVolumeM3)<=0){loaded.add(p.getId());w=nw;v=nv;}else excluded.add(p.getId());}return new Plan(shipmentId,maxWeightKg,maxVolumeM3,w,v,w.multiply(BigDecimal.valueOf(100)).divide(maxWeightKg,2,RoundingMode.HALF_UP),v.multiply(BigDecimal.valueOf(100)).divide(maxVolumeM3,2,RoundingMode.HALF_UP),loaded,excluded);}
 public record Plan(UUID shipmentId,BigDecimal maxWeightKg,BigDecimal maxVolumeM3,BigDecimal loadedWeightKg,BigDecimal loadedVolumeM3,BigDecimal weightUtilizationPercent,BigDecimal volumeUtilizationPercent,List<UUID> loadedPieceIds,List<UUID> excludedPieceIds){}
}
