package com.logiplatform.controller;

import com.logiplatform.dto.ModeOperationsDtos;
import com.logiplatform.model.*;
import com.logiplatform.service.ModeOperationsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ModeOperationsController {
    private final ModeOperationsService service;

    public ModeOperationsController(ModeOperationsService service) {
        this.service = service;
    }

    @GetMapping("/ocean/voyages")
    public List<OceanVoyage> voyages() {
        return service.voyages();
    }

    @PostMapping("/ocean/voyages")
    public OceanVoyage voyage(@Valid @RequestBody ModeOperationsDtos.VoyageRequest r) {
        return service.createVoyage(r);
    }

    @GetMapping("/ocean/shipments/{shipmentId}/containers")
    public List<OceanContainer> containers(@PathVariable UUID shipmentId) {
        return service.containers(shipmentId);
    }

    @PostMapping("/ocean/shipments/{shipmentId}/containers")
    public OceanContainer container(@PathVariable UUID shipmentId,
            @Valid @RequestBody ModeOperationsDtos.ContainerRequest r) {
        return service.createContainer(shipmentId, r);
    }

    @GetMapping("/ocean/bookings")
    public List<OceanBooking> bookings() {
        return service.bookings();
    }

    @PostMapping("/ocean/bookings")
    public OceanBooking booking(@Valid @RequestBody ModeOperationsDtos.BookingRequest r) {
        return service.createBooking(r);
    }

    @GetMapping("/road/consignments")
    public List<RoadConsignment> roads() {
        return service.roads();
    }

    @PostMapping("/road/shipments/{shipmentId}/consignment")
    public RoadConsignment road(@PathVariable UUID shipmentId, @RequestBody ModeOperationsDtos.RoadRequest r) {
        return service.createRoad(shipmentId, r);
    }

    @GetMapping("/rail/consignments")
    public List<RailConsignment> rails() {
        return service.rails();
    }

    @PostMapping("/rail/shipments/{shipmentId}/consignment")
    public RailConsignment rail(@PathVariable UUID shipmentId, @RequestBody ModeOperationsDtos.RailRequest r) {
        return service.createRail(shipmentId, r);
    }
}
