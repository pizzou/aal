package com.logiplatform.dto;

import java.util.List;
import java.util.UUID;

public final class LoadPlanDtos {

    private LoadPlanDtos() {}

    public record LoadPlanResponse(
            UUID vehicleId,
            int vehicleCapacityKg,
            int totalWeightLoadedKg,
            double utilizationPercent,
            List<PlannedShipment> selected,
            List<PlannedShipment> excluded
    ) {}

    public record PlannedShipment(
            UUID shipmentId, String referenceCode, int weightKg
    ) {}
}
