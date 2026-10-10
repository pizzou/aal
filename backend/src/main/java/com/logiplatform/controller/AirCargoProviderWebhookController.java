package com.logiplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.integration.security.WebhookSecurityService;
import com.logiplatform.service.ShipmentEtaTrackingService;
import com.logiplatform.tenancy.TenantContext;
import com.logiplatform.repository.AwbRecordRepository;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.service.AirlineDeadLetterService;
import com.logiplatform.service.control.BookingStateMachineService;
import com.logiplatform.integration.control.IntegrationMetricsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/public/integrations/air-cargo/webhooks")
public class AirCargoProviderWebhookController {
    private final ObjectMapper mapper; private final ShipmentEtaTrackingService eta; private final BookingStateMachineService states; private final IntegrationMetricsService metrics; private final AwbRecordRepository awbs; private final AirCargoBookingRepository bookings; private final AirlineDeadLetterService deadLetters; private final WebhookSecurityService security; private final UUID tenantId;
    public AirCargoProviderWebhookController(ObjectMapper mapper,ShipmentEtaTrackingService eta,AwbRecordRepository awbs,AirCargoBookingRepository bookings,AirlineDeadLetterService deadLetters,BookingStateMachineService states,IntegrationMetricsService metrics,WebhookSecurityService security,@Value("${app.single-tenant.id}") String tenant){this.mapper=mapper;this.eta=eta;this.states=states;this.metrics=metrics;this.awbs=awbs;this.bookings=bookings;this.deadLetters=deadLetters;this.security=security;this.tenantId=UUID.fromString(tenant);}

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String,Object>> receive(
            @PathVariable String provider,
            @RequestHeader(value="X-AAL-Signature", required=false) String signature,
            @RequestHeader(value="X-Signature", required=false) String altSignature,
            @RequestHeader(value="X-Timestamp", required=false) String timestamp,
            @RequestHeader(value="X-Event-Id", required=false) String eventId,
            @RequestHeader(value="X-Nonce", required=false) String nonce,
            @RequestBody String body) {
        TenantContext.setTenantId(tenantId);
        try {
            String effectiveSignature = signature == null ? altSignature : signature;
            var verification = security.verify(provider, body, effectiveSignature, timestamp, nonce, eventId);
            if (!verification.accepted()) {
                metrics.webhook(verification.reason());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("accepted", false, "error", verification.reason()));
            }

            try {
                JsonNode n = mapper.readTree(body);
                String shipmentId = n.path("shipmentId").asText(null);
                String eventType = n.path("eventType").asText("").toUpperCase(Locale.ROOT);
                if (eventType.startsWith("BOOKING_")) {
                    String providerReference = n.path("providerReference").asText(null);
                    var booking = providerReference == null ? null
                            : bookings.findByTenantIdAndProviderReference(tenantId, providerReference).orElse(null);
                    if (booking == null) {
                        security.processed(provider, eventId, false, "BOOKING_NOT_FOUND");
                        metrics.webhook("BOOKING_NOT_FOUND");
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("accepted", false, "error", "Booking not found"));
                    }
                    if ("BOOKING_CONFIRMED".equals(eventType)) {
                        booking.confirm(n.has("confirmedWeightKg") ? n.get("confirmedWeightKg").decimalValue() : booking.getRequestedWeightKg(),
                                providerReference, n.path("confirmationNumber").asText(providerReference), body);
                        states.transition(booking, "CONFIRMED", "PROVIDER_WEBHOOK_CONFIRMED", eventId);
                    } else if ("BOOKING_CANCELLED".equals(eventType)) {
                        booking.cancel("PROVIDER_WEBHOOK", body);
                        states.transition(booking, "CANCELLED", "PROVIDER_WEBHOOK_CANCELLED", eventId);
                    } else {
                        booking.fail(body);
                        states.transition(booking, "FAILED", "PROVIDER_WEBHOOK_FAILED", eventId);
                    }
                    bookings.save(booking);
                    security.processed(provider, eventId, true, null);
                    metrics.webhook("PROCESSED");
                    return ResponseEntity.accepted().body(Map.of("accepted", true, "type", "BOOKING"));
                }

                if (eventType.startsWith("AWB_")) {
                    String awbNumber = n.path("awbNumber").asText(null);
                    String carrierReference = n.path("carrierReference").asText(null);
                    var awb = (awbNumber != null
                            ? awbs.findByTenantIdAndAwbNumber(tenantId, awbNumber)
                            : Optional.<com.logiplatform.model.AwbRecord>empty())
                            .orElseGet(() -> carrierReference == null ? null
                                    : awbs.findByTenantIdAndCarrierReference(tenantId, carrierReference).orElse(null));
                    if (awb == null) {
                        security.processed(provider, eventId, false, "AWB_NOT_FOUND");
                        metrics.webhook("AWB_NOT_FOUND");
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("accepted", false, "error", "AWB not found"));
                    }
                    awb.acceptance("AWB_ACCEPTED".equals(eventType) ? "ACCEPTED" : "REJECTED",
                            carrierReference, n.path("oneRecordReference").asText(null));
                    awbs.save(awb);
                    security.processed(provider, eventId, true, null);
                    metrics.webhook("PROCESSED");
                    return ResponseEntity.accepted().body(Map.of("accepted", true, "type", "AWB"));
                }

                String flightStatus = n.path("flightStatus").asText(n.path("status").asText("UNKNOWN"));
                var status = new com.logiplatform.integration.AirCargoProviderPort.FlightStatus(
                        flightStatus, instant(n, "scheduledDeparture"), instant(n, "estimatedDeparture"),
                        instant(n, "actualDeparture"), instant(n, "scheduledArrival"),
                        instant(n, "estimatedArrival"), instant(n, "actualArrival"),
                        n.path("departureDelayMinutes").asInt(0), n.path("arrivalDelayMinutes").asInt(0),
                        n.path("providerEventId").asText(eventId), body);
                if (shipmentId == null) {
                    security.processed(provider, eventId, false, "SHIPMENT_ID_REQUIRED");
                    metrics.webhook("SHIPMENT_ID_REQUIRED");
                    return ResponseEntity.badRequest()
                            .body(Map.of("accepted", false, "error", "shipmentId is required for flight events"));
                }
                eta.apply(UUID.fromString(shipmentId), status, provider.toUpperCase(Locale.ROOT));
                security.processed(provider, eventId, true, null);
                metrics.webhook("PROCESSED");
                return ResponseEntity.accepted().body(Map.of("accepted", true));
            } catch (Exception e) {
                metrics.webhook("PROCESSING_FAILED");
                try {
                    security.processed(provider, eventId, false, e.getMessage());
                    deadLetters.enqueue(provider.toUpperCase(Locale.ROOT), "WEBHOOK", null, eventId, body, e.getMessage());
                } catch (Exception ignored) {
                    // Preserve the generic provider response even if audit/dead-letter storage is unavailable.
                }
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(Map.of("accepted", false, "error", "Webhook payload could not be processed"));
            }
        } finally {
            // This endpoint bypasses the JWT filter by design. Always clear the
            // manually installed tenant, including verifier exceptions and early returns.
            TenantContext.clear();
        }
    }
    private static Instant instant(JsonNode n,String key){String s=n.path(key).asText(null);if(s==null||s.isBlank())return null;try{return Instant.parse(s);}catch(Exception e){return null;}}
}
