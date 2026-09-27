package com.logiplatform.controller;

import com.logiplatform.service.ShipmentConsolidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/operations/consolidations")
public class ShipmentConsolidationController {
    private final ShipmentConsolidationService service;
    public ShipmentConsolidationController(ShipmentConsolidationService service) { this.service = service; }

    public record CreateRequest(@NotBlank String reference, @NotBlank String mode, String masterReference,
                                String origin, String destination, Instant plannedDeparture,
                                Instant plannedArrival, String notes) {}
    public record MemberRequest(@NotNull UUID shipmentId, String houseReference) {}

    @GetMapping
    public List<Map<String,Object>> list() { return service.list(); }

    @GetMapping("/{id}")
    public Map<String,Object> get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public Map<String,Object> create(@Valid @RequestBody CreateRequest r) {
        return service.create(r.reference(), r.mode(), r.masterReference(), r.origin(), r.destination(),
                r.plannedDeparture(), r.plannedArrival(), r.notes());
    }

    @PostMapping("/{id}/members")
    public Map<String,Object> addMember(@PathVariable UUID id, @Valid @RequestBody MemberRequest r) {
        return service.addMember(id, r.shipmentId(), r.houseReference());
    }

    @DeleteMapping("/{id}/members/{shipmentId}")
    public void removeMember(@PathVariable UUID id, @PathVariable UUID shipmentId) {
        service.removeMember(id, shipmentId);
    }
}
