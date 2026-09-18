package com.logiplatform.controller;

import com.logiplatform.service.TripService;


import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import static com.logiplatform.dto.TmsDtos.*;



@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    public ResponseEntity<TripResponse> create(@Valid @RequestBody CreateTripRequest request) {
        return ResponseEntity.ok(tripService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<TripResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(tripService.list(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TripResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(tripService.get(id));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<TripResponse> start(@PathVariable UUID id) {
        return ResponseEntity.ok(tripService.start(id));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<TripResponse> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(tripService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<TripResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(tripService.cancel(id));
    }
}

