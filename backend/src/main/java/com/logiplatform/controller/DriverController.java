package com.logiplatform.controller;

import com.logiplatform.service.DriverService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static com.logiplatform.dto.TmsDtos.*;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @PostMapping
    public ResponseEntity<DriverResponse> create(@Valid @RequestBody CreateDriverRequest request) {
        return ResponseEntity.ok(driverService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<DriverResponse>> list() {
        return ResponseEntity.ok(driverService.list());
    }
}
