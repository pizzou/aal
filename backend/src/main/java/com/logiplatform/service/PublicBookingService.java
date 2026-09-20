package com.logiplatform.service;

import com.logiplatform.dto.CommandCenterShipmentDtos.UpdateRequest;
import com.logiplatform.dto.ShipmentDtos.CreateShipmentRequest;
import com.logiplatform.dto.ShipmentDtos.ShipmentResponse;
import com.logiplatform.dto.ShipmentDtos.UpdateNotificationEmailRequest;
import com.logiplatform.dto.ShipmentDtos.UpdateStatusRequest;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Single booking path for public quote-to-book workflows.
 *
 * The Command Center mapping below matches the 30-field
 * CommandCenterShipmentDtos.UpdateRequest record exactly.
 */
@Service
public class PublicBookingService {

    private final ShipmentService shipments;
    private final PublicQuoteRequestService publicQuotes;
    private final MailService mail;

    public PublicBookingService(
            ShipmentService shipments,
            PublicQuoteRequestService publicQuotes,
            MailService mail) {
        this.shipments = shipments;
        this.publicQuotes = publicQuotes;
        this.mail = mail;
    }

    @Transactional
    public BookingResult bookFromQuoteRequest(
            String requestToken,
            String selectedMode,
            String customerReference) {

        PublicQuoteRequestService.BookingContext context =
                publicQuotes.prepareBooking(requestToken, selectedMode);

        UUID previousTenant = TenantContext.getTenantId();
        TenantContext.setTenantId(context.tenantId());

        try {
            String reference =
                    normalizeReference(customerReference, context.requestId());

            ShipmentResponse shipment = shipments.create(
                    new CreateShipmentRequest(
                            reference,
                            context.origin().trim(),
                            context.destination().trim(),
                            context.mode(),
                            null,
                            null));

            shipment = shipments.updateStatus(
                    shipment.id(),
                    new UpdateStatusRequest("BOOKED"));

            shipments.updateCommandCenter(
                    shipment.id(),
                    commandCenterRequest(
                            context.company(),
                            context.contactName(),
                            context.commodity(),
                            context.origin(),
                            context.destination(),
                            context.packages(),
                            "AIR".equals(context.mode()) ? null : null,
                            context.mode(),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            nz(context.quotedAmount()),
                            "PUBLIC WEB BOOKING",
                            safeCurrency(context.currency())));

            String email = blankToNull(context.email());

            if (email != null) {
                shipments.updateNotificationEmail(
                        shipment.id(),
                        new UpdateNotificationEmailRequest(email));
            }

            publicQuotes.markBooked(
                    requestToken,
                    shipment.id());

            final ShipmentResponse confirmedShipment = shipment;

            afterCommit(() ->
                    mail.sendPublicBookingConfirmation(
                            context.email(),
                            context.contactName(),
                            confirmedShipment.referenceCode(),
                            confirmedShipment.trackingToken().toString(),
                            context.origin().trim()
                                    + " → "
                                    + context.destination().trim()));

            return new BookingResult(
                    confirmedShipment.id(),
                    confirmedShipment.referenceCode(),
                    confirmedShipment.trackingToken(),
                    confirmedShipment.status(),
                    "Booking received by AAL");

        } finally {
            restoreTenant(previousTenant);
        }
    }

    @Transactional
    public BookingResult bookDirect(
            String origin,
            String destination,
            String serviceType,
            String customerReference,
            String carrier,
            String company,
            String contactName,
            String email,
            String commodity,
            Integer packages) {

        requireText(origin, "Origin is required");
        requireText(destination, "Destination is required");

        String mode = normalizeMode(serviceType);

        String reference =
                normalizeReference(
                        customerReference,
                        UUID.randomUUID());

        ShipmentResponse shipment = shipments.create(
                new CreateShipmentRequest(
                        reference,
                        origin.trim(),
                        destination.trim(),
                        mode,
                        blankToNull(carrier),
                        null));

        shipment = shipments.updateStatus(
                shipment.id(),
                new UpdateStatusRequest("BOOKED"));

        shipments.updateCommandCenter(
                shipment.id(),
                commandCenterRequest(
                        company,
                        contactName,
                        commodity,
                        origin,
                        destination,
                        packages,
                        "AIR".equals(mode)
                                ? blankToNull(carrier)
                                : null,
                        mode,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "PUBLIC WEB DIRECT BOOKING",
                        "USD"));

        String normalizedEmail =
                blankToNull(email);

        if (normalizedEmail != null) {
            shipments.updateNotificationEmail(
                    shipment.id(),
                    new UpdateNotificationEmailRequest(
                            normalizedEmail));
        }

        final ShipmentResponse confirmedShipment = shipment;

        afterCommit(() ->
                mail.sendPublicBookingConfirmation(
                        normalizedEmail,
                        blankToNull(contactName),
                        confirmedShipment.referenceCode(),
                        confirmedShipment.trackingToken().toString(),
                        origin.trim()
                                + " → "
                                + destination.trim()));

        return new BookingResult(
                confirmedShipment.id(),
                confirmedShipment.referenceCode(),
                confirmedShipment.trackingToken(),
                confirmedShipment.status(),
                "Booking received by AAL");
    }

    /**
     * Exact 30-field mapping for
     * CommandCenterShipmentDtos.UpdateRequest.
     */
    private static UpdateRequest commandCenterRequest(
            String company,
            String contactName,
            String commodity,
            String origin,
            String destination,
            Integer packages,
            String airlineUsed,
            String serviceType,
            BigDecimal supplierCost,
            BigDecimal otherCost,
            BigDecimal clientRevenue,
            String notes,
            String currency) {

        return new UpdateRequest(
                blankToNull(company),                // 1
                blankToNull(contactName),             // 2
                blankToNull(commodity),               // 3
                null,                                 // 4
                blankToNull(origin),                  // 5
                null,                                 // 6
                blankToNull(destination),             // 7
                null,                                 // 8
                null,                                 // 9
                packages,                              // 10
                blankToNull(airlineUsed),              // 11
                normalizeMode(serviceType),            // 12
                "PUBLIC_WEB",                          // 13
                nz(supplierCost),                     // 14
                nz(otherCost),                        // 15
                nz(clientRevenue),                    // 16
                BigDecimal.ZERO,                      // 17
                BigDecimal.ZERO,                      // 18
                BigDecimal.ZERO,                      // 19
                "UNPAID",                             // 20
                null,                                 // 21
                null,                                 // 22
                null,                                 // 23
                null,                                 // 24
                null,                                 // 25
                null,                                 // 26
                blankToNull(notes),                   // 27
                safeCurrency(currency),               // 28
                LocalDate.now(),                      // 29
                "BOOKED"                              // 30
        );
    }

    private static void afterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            callback.run();
            return;
        }

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {
                                callback.run();
                            }
                        });
    }

    private static void restoreTenant(
            UUID previousTenant) {

        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(previousTenant);
        }
    }

    private static void requireText(
            String value,
            String message) {

        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    message);
        }
    }

    private static String normalizeReference(
            String raw,
            UUID fallbackSeed) {

        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }

        return "WEB-"
                + fallbackSeed
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizeMode(
            String raw) {

        String value =
                raw == null
                        ? "ROAD"
                        : raw.trim()
                                .toUpperCase(Locale.ROOT);

        return switch (value) {
            case "AIR",
                 "AIRFREIGHT",
                 "AIR FREIGHT" -> "AIR";

            case "SEA",
                 "SEA FREIGHT",
                 "SEAFREIGHT" -> "SEA";

            case "ROAD",
                 "ROAD FREIGHT",
                 "ROADFREIGHT" -> "ROAD";

            case "RAIL",
                 "RAIL FREIGHT",
                 "RAILFREIGHT" -> "RAIL";

            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid transport mode: " + raw);
        };
    }

    private static String blankToNull(
            String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private static BigDecimal nz(
            BigDecimal value) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private static String safeCurrency(
            String value) {

        return value == null || value.isBlank()
                ? "USD"
                : value.trim()
                        .toUpperCase(Locale.ROOT);
    }

    public record BookingResult(
            UUID shipmentId,
            String reference,
            UUID trackingToken,
            String status,
            String message) {
    }
}