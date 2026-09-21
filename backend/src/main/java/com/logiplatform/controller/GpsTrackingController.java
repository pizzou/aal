package com.logiplatform.controller;

import com.logiplatform.service.GpsTrackingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.logiplatform.dto.GpsDtos.*;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/gps")
public class GpsTrackingController {
    private final GpsTrackingService gpsTrackingService;
    public GpsTrackingController(GpsTrackingService s){gpsTrackingService=s;}
    @PostMapping public ResponseEntity<PositionResponse> recordPosition(@PathVariable UUID vehicleId,@Valid @RequestBody RecordPositionRequest request,@RequestParam(defaultValue="MANUAL") String source,@RequestParam(required=false) String deviceId,@RequestParam(required=false) Double accuracyMeters,@RequestParam(required=false) Double batteryPercent){return ResponseEntity.ok(gpsTrackingService.recordPosition(vehicleId,request,source,deviceId,accuracyMeters,batteryPercent));}
    @GetMapping("/latest") public ResponseEntity<PositionResponse> latest(@PathVariable UUID vehicleId){return ResponseEntity.ok(gpsTrackingService.latest(vehicleId));}
    @GetMapping("/status") public ResponseEntity<GpsStatusResponse> status(@PathVariable UUID vehicleId){return ResponseEntity.ok(gpsTrackingService.status(vehicleId));}
    @GetMapping("/history") public ResponseEntity<Page<PositionResponse>> history(@PathVariable UUID vehicleId,Pageable pageable){return ResponseEntity.ok(gpsTrackingService.history(vehicleId,pageable));}
}
