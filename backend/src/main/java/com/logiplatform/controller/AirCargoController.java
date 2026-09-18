package com.logiplatform.controller;

import com.logiplatform.service.AirCargoBookingService;
import com.logiplatform.service.AirCargoConnectivityService;
import com.logiplatform.service.RouteOptimizationService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.logiplatform.dto.AirCargoDtos.*;

@RestController
@RequestMapping("/api/air-cargo")
public class AirCargoController {

    private final AirCargoConnectivityService connectivity;
    private final AirCargoBookingService booking;
    private final RouteOptimizationService routes;

    public AirCargoController(
            AirCargoConnectivityService connectivity,
            AirCargoBookingService booking,
            RouteOptimizationService routes) {

        this.connectivity = connectivity;
        this.booking = booking;
        this.routes = routes;
    }

    @PostMapping("/flights")
    public FlightResponse ingest(@Valid @RequestBody FlightIngestRequest request) {
        return FlightResponse.from(connectivity.ingest(request));
    }

    @PostMapping("/flights/search")
    public List<FlightResponse> search(@Valid @RequestBody FlightSearchRequest request) {

        Instant from = request.from() == null
                ? Instant.now()
                : request.from();

        Instant to = request.to() == null
                ? from.plus(Duration.ofDays(14))
                : request.to();

        return connectivity
                .schedules(
                        request.origin(),
                        request.destination(),
                        from,
                        to,
                        request.weightKg())
                .stream()
                .map(FlightResponse::from)
                .toList();
    }

    @GetMapping("/capacity")
    public AirCargoConnectivityService.CapacityResponse capacity(
            @RequestParam String flightNumber,
            @RequestParam Instant date) {

        return connectivity.capacity(flightNumber, date);
    }

    @PostMapping("/bookings")
    public BookingResponse book(@Valid @RequestBody BookRequest request) {
        return booking.book(request);
    }

    @GetMapping("/bookings")
    public List<BookingResponse> bookings() {
        return booking.list();
    }

    @PostMapping("/routes/optimize")
    public List<RouteOptimizationService.RouteOption> optimize(
            @Valid @RequestBody FlightSearchRequest request) {

        Instant from = request.from() == null
                ? Instant.now()
                : request.from();

        Instant to = request.to() == null
                ? from.plus(Duration.ofDays(14))
                : request.to();

        return routes.optimize(
                request.origin(),
                request.destination(),
                from,
                to,
                request.weightKg());
    }
}

