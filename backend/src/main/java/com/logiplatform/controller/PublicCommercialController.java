package com.logiplatform.controller;

import com.logiplatform.dto.CommercialDtos.QuoteRequest;
import com.logiplatform.dto.CommercialDtos.QuoteResponse;
import com.logiplatform.dto.ShipmentDtos.CreateShipmentRequest;
import com.logiplatform.dto.ShipmentDtos.ShipmentResponse;
import com.logiplatform.service.CommercialOperationsService;
import com.logiplatform.service.ShipmentService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/commercial")
public class PublicCommercialController {

    private final CommercialOperationsService commercial;
    private final ShipmentService shipments;
    private final UUID tenantId;

    public PublicCommercialController(
            CommercialOperationsService commercial,
            ShipmentService shipments,
            @Value("${app.single-tenant.id}") UUID tenantId) {
        this.commercial = commercial;
        this.shipments = shipments;
        this.tenantId = tenantId;
    }

    @PostMapping("/quotes")
    public ResponseEntity<QuoteResponse> requestQuote(@Valid @RequestBody PublicQuoteRequest request) {
        return withTenant(() -> {
            String quoteId = "WEB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String client = firstNonBlank(request.company(), request.contactName(), request.email());
            String route = request.origin().trim() + " → " + request.destination().trim();
            String notes = joinNotes(request.email(), request.phone(), request.packages(), request.volumeCbm(), request.notes());
            QuoteRequest quote = new QuoteRequest(
                    quoteId,
                    LocalDate.now(),
                    client,
                    route,
                    request.serviceType(),
                    request.commodity(),
                    nz(request.chargeableWeightKg()),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LocalDate.now().plusDays(7),
                    "REQUESTED",
                    "WEB",
                    null,
                    notes,
                    "PUBLIC_REQUEST");
            return ResponseEntity.ok(commercial.createQuote(quote));
        });
    }

    @PostMapping("/bookings")
    public ResponseEntity<PublicBookingResponse> book(@Valid @RequestBody PublicBookingRequest request) {
        return withTenant(() -> {
            String reference = request.customerReference() == null || request.customerReference().isBlank()
                    ? "WEB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
                    : request.customerReference().trim();
            ShipmentResponse shipment = shipments.create(new CreateShipmentRequest(
                    reference,
                    request.origin().trim(),
                    request.destination().trim(),
                    request.serviceType(),
                    request.carrier(),
                    null));
            return ResponseEntity.ok(new PublicBookingResponse(
                    shipment.id(),
                    shipment.referenceCode(),
                    shipment.trackingToken(),
                    shipment.status(),
                    "Booking received by AAL"));
        });
    }

    private <T> T withTenant(java.util.function.Supplier<T> action) {
        try {
            TenantContext.setTenantId(tenantId);
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "Web customer";
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static String joinNotes(String email, String phone, Integer packages, BigDecimal volume, String notes) {
        return "PUBLIC WEB REQUEST | Email: " + nullToDash(email)
                + " | Phone: " + nullToDash(phone)
                + " | Packages: " + (packages == null ? "-" : packages)
                + " | Volume CBM: " + (volume == null ? "-" : volume)
                + (notes == null || notes.isBlank() ? "" : " | " + notes.trim());
    }

    private static String nullToDash(String value) { return value == null || value.isBlank() ? "-" : value.trim(); }

    public record PublicQuoteRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String serviceType,
            String commodity,
            @PositiveOrZero BigDecimal chargeableWeightKg,
            @PositiveOrZero BigDecimal volumeCbm,
            @PositiveOrZero Integer packages,
            String company,
            String contactName,
            @Email String email,
            String phone,
            String notes) {}

    public record PublicBookingRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String serviceType,
            String customerReference,
            String carrier,
            String company,
            String contactName,
            @Email String email,
            String phone) {}

    public record PublicBookingResponse(UUID shipmentId, String reference, UUID trackingToken, String status, String message) {}
}
