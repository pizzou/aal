package com.logiplatform.controller;

import com.logiplatform.model.TransportLeg;
import com.logiplatform.repository.TransportLegRepository;
import com.logiplatform.service.ShipmentService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.logiplatform.dto.TransportLegDtos.CreateRequest;
import static com.logiplatform.dto.TransportLegDtos.Response;

@RestController
@RequestMapping("/api/shipments")
public class TransportLegController {

    private final TransportLegRepository repository;
    private final ShipmentService shipments;

    public TransportLegController(TransportLegRepository repository, ShipmentService shipments) {
        this.repository = repository;
        this.shipments = shipments;
    }

    @PostMapping("/{shipmentId}/legs")
    public Response create(
            @PathVariable UUID shipmentId,
            @Valid @RequestBody CreateRequest request) {
        if (!shipmentId.equals(request.shipmentId())) {
            throw new IllegalArgumentException("shipmentId in path and body must match");
        }

        shipments.get(shipmentId);

        TransportLeg leg = new TransportLeg(
                TenantContext.getTenantId(),
                shipmentId,
                request.sequenceNo(),
                request.mode().trim().toUpperCase(),
                request.origin().trim(),
                request.destination().trim(),
                request.carrierName() == null ? null : request.carrierName().trim(),
                request.plannedDeparture(),
                request.plannedArrival());

        return Response.from(repository.save(leg));
    }

    @GetMapping("/{shipmentId}/legs")
    public List<Response> list(@PathVariable UUID shipmentId) {
        shipments.get(shipmentId);
        return repository
                .findAllByTenantIdAndShipmentIdOrderBySequenceNoAsc(
                        TenantContext.getTenantId(), shipmentId)
                .stream()
                .map(Response::from)
                .toList();
    }
}
