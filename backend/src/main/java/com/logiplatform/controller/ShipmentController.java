package com.logiplatform.controller;

import com.logiplatform.model.NotificationResponse;
import com.logiplatform.service.ShipmentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.logiplatform.dto.CommandCenterShipmentDtos.UpdateRequest;
import static com.logiplatform.dto.ShipmentDtos.*;

/**
 * Canonical shipment API, including the AAL operational update
 * contract.
 */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@Valid @RequestBody CreateShipmentRequest request) {
        return ResponseEntity.ok(shipmentService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<ShipmentResponse>> list(
            Pageable pageable,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(shipmentService.list(pageable, q, mode, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(shipmentService.get(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ShipmentResponse> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(shipmentService.updateStatus(id, request));
    }

    @PatchMapping("/{id}/command-center")
    public ResponseEntity<ShipmentResponse> updateCommandCenter(
            @PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return ResponseEntity.ok(shipmentService.updateCommandCenter(id, request));
    }

    @PatchMapping("/{id}/weight")
    public ResponseEntity<ShipmentResponse> updateWeight(
            @PathVariable UUID id, @Valid @RequestBody UpdateWeightRequest request) {
        return ResponseEntity.ok(shipmentService.updateWeight(id, request));
    }

    @PatchMapping("/{id}/notification-email")
    public ResponseEntity<ShipmentResponse> updateNotificationEmail(
            @PathVariable UUID id, @Valid @RequestBody UpdateNotificationEmailRequest request) {
        return ResponseEntity.ok(shipmentService.updateNotificationEmail(id, request));
    }

    @PatchMapping("/{id}/flight-number")
    public ResponseEntity<ShipmentResponse> updateFlightNumber(
            @PathVariable UUID id, @Valid @RequestBody UpdateFlightNumberRequest request) {
        return ResponseEntity.ok(shipmentService.updateFlightNumber(id, request));
    }

    @GetMapping("/{id}/notifications")
    public ResponseEntity<Page<NotificationResponse>> notifications(
            @PathVariable UUID id, Pageable pageable) {
        return ResponseEntity.ok(shipmentService.notificationHistory(id, pageable));
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<TrackingEventResponse> addTrackingEvent(
            @PathVariable UUID id, @Valid @RequestBody AddTrackingEventRequest request) {
        return ResponseEntity.ok(shipmentService.addTrackingEvent(id, request));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<TrackingEventResponse>> trackingHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(shipmentService.trackingHistory(id));
    }
}
