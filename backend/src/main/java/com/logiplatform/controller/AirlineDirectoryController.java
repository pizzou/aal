package com.logiplatform.controller;

import com.logiplatform.service.AirlineDirectoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated airline reference directory for the AAL air-cargo desk. */
@RestController
@RequestMapping("/api/air-cargo/airlines")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','AIR_CARGO')")
public class AirlineDirectoryController {
    private final AirlineDirectoryService directory;

    public AirlineDirectoryController(AirlineDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    public ResponseEntity<AirlineDirectoryService.DirectoryResponse> list() {
        return ResponseEntity.ok(directory.list());
    }

    @GetMapping("/{iataCode}")
    public ResponseEntity<AirlineDirectoryService.Airline> get(@PathVariable String iataCode) {
        return ResponseEntity.ok(directory.find(iataCode));
    }
}
