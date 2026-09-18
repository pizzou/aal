package com.logiplatform.controller;

import com.logiplatform.model.CargoPiece;
import com.logiplatform.repository.CargoPieceRepository;
import com.logiplatform.service.AirLoadPlanningService;
import com.logiplatform.service.ShipmentService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/air-cargo")
public class AirLoadPlanningController {
    private final CargoPieceRepository pieces;
    private final AirLoadPlanningService planner;
    private final ShipmentService shipments;

    public AirLoadPlanningController(
            CargoPieceRepository pieces,
            AirLoadPlanningService planner,
            ShipmentService shipments) {
        this.pieces = pieces;
        this.planner = planner;
        this.shipments = shipments;
    }

    public record PieceRequest(
            @NotNull UUID shipmentId,
            @NotNull @Positive Integer pieceNo,
            @NotNull @Positive BigDecimal lengthCm,
            @NotNull @Positive BigDecimal widthCm,
            @NotNull @Positive BigDecimal heightCm,
            @NotNull @Positive BigDecimal weightKg,
            boolean stackable,
            boolean temperatureControlled) {}

    @PostMapping("/pieces")
    public Map<String, Object> piece(@Valid @RequestBody PieceRequest request) {
        shipments.get(request.shipmentId());
        CargoPiece piece = pieces.save(new CargoPiece(
                TenantContext.getTenantId(), request.shipmentId(), request.pieceNo(),
                request.lengthCm(), request.widthCm(), request.heightCm(), request.weightKg(),
                request.stackable(), request.temperatureControlled()));
        return Map.of(
                "id", piece.getId(),
                "shipmentId", piece.getShipmentId(),
                "pieceNo", piece.getPieceNo(),
                "volumeM3", piece.volumeM3());
    }

    public record PlanRequest(
            @NotNull UUID shipmentId,
            @NotNull @Positive BigDecimal maxWeightKg,
            @NotNull @Positive BigDecimal maxVolumeM3) {}

    @PostMapping("/load-plans")
    public AirLoadPlanningService.Plan plan(@Valid @RequestBody PlanRequest request) {
        shipments.get(request.shipmentId());
        return planner.plan(request.shipmentId(), request.maxWeightKg(), request.maxVolumeM3());
    }
}
