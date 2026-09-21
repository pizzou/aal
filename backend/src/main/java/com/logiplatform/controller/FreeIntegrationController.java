package com.logiplatform.controller;

import com.logiplatform.service.FreeIntegrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.logiplatform.dto.FreeIntegrationDtos.*;

@RestController
@RequestMapping("/api/integrations/free")
public class FreeIntegrationController {
    private final FreeIntegrationService service;

    public FreeIntegrationController(FreeIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public ResponseEntity<List<Provider>> providers() {
        return ResponseEntity.ok(service.providers());
    }

    @GetMapping("/weather")
    public ResponseEntity<Map<String, Object>> weather(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        return ResponseEntity.ok(service.weather(latitude, longitude));
    }

    @GetMapping("/route")
    public ResponseEntity<Map<String, Object>> route(
            @RequestParam double fromLat,
            @RequestParam double fromLon,
            @RequestParam double toLat,
            @RequestParam double toLon) {
        return ResponseEntity.ok(service.route(fromLat, fromLon, toLat, toLon));
    }

    @PostMapping("/amadeus/token-check")
    public ResponseEntity<Map<String, Object>> amadeusTokenCheck() {
        return ResponseEntity.ok(Map.of(
                "configured", true,
                "tokenType", service.amadeusToken().getOrDefault("token_type", "Bearer")));
    }

    @PostMapping("/amadeus/flight-search")
    public ResponseEntity<Map<String, Object>> flightSearch(@RequestBody FlightSearchRequest request) {
        return ResponseEntity.ok(service.amadeusFlightSearch(
                request.origin(),
                request.destination(),
                request.departureDate(),
                request.adults()));
    }

    @PostMapping("/paypal/token-check")
    public ResponseEntity<Map<String, Object>> paypalTokenCheck() {
        return ResponseEntity.ok(Map.of(
                "configured", true,
                "tokenType", service.paypalToken().getOrDefault("token_type", "Bearer")));
    }
}
