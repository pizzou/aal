package com.logiplatform.service;

import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.logiplatform.dto.AirCargoDtos.*;

@Service
public class AirCargoBookingService {
    private final AirCargoBookingRepository bookings;
    private final AirCargoFlightRepository flights;
    private final ShipmentService shipments;
    private final ExternalGatewayService external;

    public AirCargoBookingService(AirCargoBookingRepository b, AirCargoFlightRepository f,
            ShipmentService s, ExternalGatewayService e) {
        bookings = b;
        flights = f;
        shipments = s;
        external = e;
    }

    @Transactional
    public BookingResponse book(BookRequest r) {
        UUID tenant = TenantContext.getTenantId();
        if (r.departureTime().isBefore(Instant.now().minusSeconds(60)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot book a flight that has already departed");
        if (r.arrivalTime() != null && !r.arrivalTime().isAfter(r.departureTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arrival must be after departure");
        if (r.originCode().equalsIgnoreCase(r.destinationCode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Origin and destination must differ");

        shipments.get(r.shipmentId());
        String idem = r.idempotencyKey().trim();
        if (idem.length() > 255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey is too long");

        var prior = bookings.findByTenantIdAndIdempotencyKey(tenant, idem).orElse(null);
        if (prior != null)
            return BookingResponse.from(prior);

        List<AirCargoFlight> candidates = flights
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        r.originCode().trim().toUpperCase(),
                        r.destinationCode().trim().toUpperCase(),
                        r.departureTime().minus(2, ChronoUnit.MINUTES),
                        r.departureTime().plus(2, ChronoUnit.MINUTES));

        AirCargoFlight reserved = null;
        if (!candidates.isEmpty()) {
            // Lock the exact persisted flight before checking and decrementing capacity.
            reserved = flights.findByTenantIdAndIdForUpdate(tenant, candidates.get(0).getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Flight disappeared during booking"));
            if (!reserved.reserve(r.weightKg()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient live capacity");
            flights.save(reserved);
        } else if (!external.carrierConfigured()) {
            // An internal booking is only valid against a persisted capacity record.
            // Never manufacture a capacity reservation when the platform has no carrier feed.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No persisted capacity is available for the requested flight");
        }

        AirCargoBooking booking = new AirCargoBooking(tenant, r.shipmentId(), r.carrierCode().trim().toUpperCase(),
                r.carrierName(), r.flightNumber().trim().toUpperCase(), r.departureTime(), r.arrivalTime(),
                r.originCode().trim().toUpperCase(), r.destinationCode().trim().toUpperCase(), r.weightKg(),
                "PENDING", external.carrierConfigured() ? "EXTERNAL_CARRIER" : "INTERNAL_CAPACITY", idem);
        booking = bookings.saveAndFlush(booking);

        try {
            if (external.carrierConfigured()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("shipmentId", r.shipmentId());
                payload.put("carrierCode", r.carrierCode());
                payload.put("carrierName", r.carrierName());
                payload.put("flightNumber", r.flightNumber());
                payload.put("departureTime", r.departureTime());
                payload.put("arrivalTime", r.arrivalTime());
                payload.put("originCode", r.originCode());
                payload.put("destinationCode", r.destinationCode());
                payload.put("weightKg", r.weightKg());
                payload.put("idempotencyKey", idem);
                Map<String, Object> response = external.submitBooking(payload);
                String status = String.valueOf(response.getOrDefault("status", "CONFIRMED")).toUpperCase();
                if (!Set.of("CONFIRMED", "HELD", "PENDING").contains(status))
                    throw new IllegalStateException("Carrier returned unsupported booking status: " + status);
                String ref = String.valueOf(response.getOrDefault("reference", idem));
                String conf = String.valueOf(response.getOrDefault("confirmationNumber", ref));
                if ("CONFIRMED".equals(status))
                    booking.confirm(r.weightKg(), ref, conf, response.toString());
                else
                    booking.pending(ref, response.toString());
            } else {
                String ref = "CAP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
                booking.confirm(r.weightKg(), ref, ref, "INTERNAL_CAPACITY_RESERVATION");
            }
        } catch (Exception ex) {
            booking.fail(ex.getMessage() == null ? "Carrier booking failed" : ex.getMessage());
            bookings.save(booking);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Carrier booking failed; no booking reservation was committed");
        }
        return BookingResponse.from(bookings.save(booking));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list() {
        return bookings.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId())
                .stream().map(BookingResponse::from).toList();
    }
}
