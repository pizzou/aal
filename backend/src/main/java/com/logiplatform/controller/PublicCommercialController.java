package com.logiplatform.controller;

import com.logiplatform.dto.PublicCommercialDtos.PublicQuoteRequestResponse;
import com.logiplatform.service.PublicBookingService;
import com.logiplatform.service.PublicQuoteRequestService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/commercial")
public class PublicCommercialController {

    private final PublicQuoteRequestService publicQuotes;
    private final PublicBookingService bookings;
    private final UUID tenantId;

    public PublicCommercialController(
            PublicQuoteRequestService publicQuotes,
            PublicBookingService bookings,
            @Value("${app.single-tenant.id}") UUID tenantId) {
        this.publicQuotes = publicQuotes;
        this.bookings = bookings;
        this.tenantId = tenantId;
    }

    @PostMapping("/quotes")
    public ResponseEntity<PublicQuoteRequestResponse> requestQuote(
            @Valid @RequestBody PublicQuoteRequest request) {
        return withTenant(() -> ResponseEntity.ok(publicQuotes.create(request)));
    }

    @GetMapping("/quote-requests/{token}")
    public ResponseEntity<PublicQuoteRequestResponse> quoteRequest(
            @PathVariable String token) {
        return ResponseEntity.ok(publicQuotes.view(token));
    }

    @PostMapping("/bookings")
    public ResponseEntity<PublicBookingService.BookingResult> book(
            @Valid @RequestBody PublicBookingRequest request) {
        return withTenant(() -> {
            PublicBookingService.BookingResult result;
            if (request.quoteRequestToken() != null && !request.quoteRequestToken().isBlank()) {
                result = bookings.bookFromQuoteRequest(
                        request.quoteRequestToken(),
                        request.selectedMode() == null || request.selectedMode().isBlank()
                                ? request.serviceType()
                                : request.selectedMode(),
                        request.customerReference());
            } else {
                result = bookings.bookDirect(
                        request.origin(),
                        request.destination(),
                        request.serviceType(),
                        request.customerReference(),
                        request.carrier(),
                        request.company(),
                        request.contactName(),
                        request.email(),
                        request.commodity(),
                        request.packages());
            }
            return ResponseEntity.ok(result);
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

    public record PublicQuoteRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String serviceType,
            String commodity,
            @PositiveOrZero BigDecimal chargeableWeightKg,
            @PositiveOrZero BigDecimal volumeCbm,
            @PositiveOrZero Integer packages,
            String company,
            @NotBlank String contactName,
            @NotBlank @Email String email,
            String phone,
            String notes) {
    }

    public record PublicBookingRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String serviceType,
            String customerReference,
            String carrier,
            String company,
            @NotBlank String contactName,
            @NotBlank @Email String email,
            String phone,
            String commodity,
            @PositiveOrZero Integer packages,
            String quoteRequestToken,
            String selectedMode) {
    }
}
