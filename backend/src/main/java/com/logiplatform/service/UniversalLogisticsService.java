package com.logiplatform.service;

import com.logiplatform.dto.UniversalLogisticsDtos;
import com.logiplatform.model.CargoItem;
import com.logiplatform.model.TransportPlanLeg;
import com.logiplatform.repository.CargoItemRepository;
import com.logiplatform.repository.TransportPlanLegRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UniversalLogisticsService {
    private final CargoItemRepository cargo;
    private final TransportPlanLegRepository legs;
    private final ShipmentService shipments;

    public UniversalLogisticsService(CargoItemRepository cargo, TransportPlanLegRepository legs, ShipmentService shipments) {
        this.cargo = cargo; this.legs = legs; this.shipments = shipments;
    }

    public java.util.Map<String,Object> capabilities() {
        return java.util.Map.of(
                "modes", List.of("AIR","SEA","ROAD","RAIL","INLAND_WATERWAY","COURIER","LAST_MILE","RORO","PROJECT_CARGO"),
                "services", List.of("FCL","LCL","CONSOLIDATION","CROSS_DOCK","WAREHOUSE","CUSTOMS","COLD_CHAIN","DANGEROUS_GOODS","PROJECT_LOGISTICS","LAST_MILE"),
                "documents", List.of("AWB","HAWB","MBL","HBL","CMR","RAIL_CONSIGNMENT","VGM","COMMERCIAL_INVOICE","PACKING_LIST","CERTIFICATE_OF_ORIGIN","CUSTOMS_DECLARATION","DG_DECLARATION","POD"));
    }

    @Transactional(readOnly = true)
    public List<CargoItem> cargo(UUID shipmentId) {
        shipments.get(shipmentId);
        return cargo.findAllByTenantIdAndShipmentIdOrderByLineNoAsc(TenantContext.getTenantId(), shipmentId);
    }

    @Transactional
    public CargoItem addCargo(UUID shipmentId, UniversalLogisticsDtos.CargoRequest request) {
        shipments.get(shipmentId);
        if (request.volumeM3() == null && (request.lengthCm() != null || request.widthCm() != null || request.heightCm() != null))
            throw new IllegalArgumentException("lengthCm, widthCm and heightCm must be supplied together");
        if (request.temperatureControlled() && request.minTemperatureC() != null && request.maxTemperatureC() != null && request.minTemperatureC().compareTo(request.maxTemperatureC()) > 0)
            throw new IllegalArgumentException("minTemperatureC cannot exceed maxTemperatureC");
        if (request.dangerousGoods() && (request.dgClass() == null || request.dgClass().isBlank()) && (request.unNumber() == null || request.unNumber().isBlank()))
            throw new IllegalArgumentException("Dangerous goods require a DG class or UN number");
        return cargo.save(new CargoItem(TenantContext.getTenantId(), shipmentId, request.lineNo(), request.description(), request.packageType(), request.quantity(), request.grossWeightKg(), request.volumeM3(), request.lengthCm(), request.widthCm(), request.heightCm(), request.hsCode(), request.countryOfOrigin(), request.declaredValue(), request.currency(), request.dangerousGoods(), request.dgClass(), request.unNumber(), request.temperatureControlled(), request.minTemperatureC(), request.maxTemperatureC(), request.stackable(), request.fragile()));
    }

    @Transactional(readOnly = true)
    public List<TransportPlanLeg> legs(UUID shipmentId) {
        shipments.get(shipmentId);
        return legs.findAllByTenantIdAndShipmentIdOrderBySequenceNoAsc(TenantContext.getTenantId(), shipmentId);
    }

    @Transactional
    public TransportPlanLeg addLeg(UUID shipmentId, UniversalLogisticsDtos.LegRequest request) {
        shipments.get(shipmentId);
        if (request.plannedDeparture() != null && request.plannedArrival() != null && !request.plannedArrival().isAfter(request.plannedDeparture()))
            throw new IllegalArgumentException("plannedArrival must be after plannedDeparture");
        return legs.save(new TransportPlanLeg(TenantContext.getTenantId(), shipmentId, request.sequenceNo(), request.mode().trim().toUpperCase(), request.carrierName(), request.carrierReference(), request.originCode(), request.originName(), request.destinationCode(), request.destinationName(), request.plannedDeparture(), request.plannedArrival(), request.equipmentType(), request.notes()));
    }
}
