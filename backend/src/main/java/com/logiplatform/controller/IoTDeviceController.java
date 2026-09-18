package com.logiplatform.controller;

import com.logiplatform.service.IoTDeviceService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.logiplatform.dto.SensorDtos.*;

@RestController
@RequestMapping("/api/iot")
public class IoTDeviceController {
    private final IoTDeviceService s;

    public IoTDeviceController(IoTDeviceService s) {
        this.s = s;
    }

    public record RegisterRequest(UUID shipmentId, String deviceCode, String secret) {
    }

    @PostMapping("/devices")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest r) {
        return s.register(r.shipmentId(), r.deviceCode(), r.secret());
    }

    @PostMapping("/devices/{deviceCode}/readings")
    public ReadingResponse ingest(@PathVariable String deviceCode, @RequestHeader("X-Device-Secret") String secret,
            @Valid @RequestBody RecordReadingRequest r) {
        return s.ingest(deviceCode, secret, r);
    }
}
