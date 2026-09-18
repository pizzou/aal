package com.logiplatform.service;

import com.logiplatform.model.SensorReading;
import com.logiplatform.repository.SensorReadingRepository;
import com.logiplatform.model.SensorThreshold;
import com.logiplatform.repository.SensorThresholdRepository;
import com.logiplatform.dto.ShipmentDtos;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;
import static com.logiplatform.dto.SensorDtos.*;



/**
 * The point of cold-chain monitoring isn't collecting temperature numbers — it's
 * catching a violation WHILE the shipment is still in transit, not discovering
 * spoiled cargo at delivery. So a reading that violates its shipment's threshold
 * doesn't just get stored: it automatically adds an EXCEPTION event to the same
 * tracking timeline built for shipment status, so anyone watching a shipment's
 * tracking history — staff or, via the public tracking portal, the customer — sees
 * the problem the moment it's reported, not buried in a separate sensor log nobody's
 * watching.
 */
@Service
public class SensorMonitoringService {

    private final SensorReadingRepository readingRepository;
    private final SensorThresholdRepository thresholdRepository;
    private final ShipmentService shipmentService;

    public SensorMonitoringService(SensorReadingRepository readingRepository,
                                    SensorThresholdRepository thresholdRepository,
                                    ShipmentService shipmentService) {
        this.readingRepository = readingRepository;
        this.thresholdRepository = thresholdRepository;
        this.shipmentService = shipmentService;
    }

    @Transactional
    public ReadingResponse recordReading(UUID shipmentId, RecordReadingRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        shipmentService.get(shipmentId); // 404s if this shipment isn't the caller's

        Instant recordedAt = request.recordedAt() != null ? request.recordedAt() : Instant.now();
        if (recordedAt.isAfter(Instant.now().plusSeconds(300)) || recordedAt.isBefore(Instant.now().minusSeconds(86400))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recordedAt is outside the accepted telemetry window");
        }
        if (request.eventId() != null && readingRepository.findByTenantIdAndEventId(tenantId, request.eventId()).isPresent()) {
            SensorReading existing = readingRepository.findByTenantIdAndEventId(tenantId, request.eventId()).get();
            boolean violation = thresholdRepository.findByTenantIdAndShipmentId(tenantId, shipmentId)
                    .map(t -> t.checkViolation(existing.getTemperatureCelsius(), existing.getHumidityPercent()) != null).orElse(false);
            return ReadingResponse.from(existing, violation);
        }

        SensorReading reading;
        try {
            reading = new SensorReading(tenantId, shipmentId, request.temperatureCelsius(),
                    request.humidityPercent(), recordedAt);
            if (request.eventId() != null) reading.setEventId(request.eventId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        readingRepository.save(reading);

        boolean violated = checkAndFlagViolation(tenantId, shipmentId, reading);
        return ReadingResponse.from(reading, violated);
    }

    private boolean checkAndFlagViolation(UUID tenantId, UUID shipmentId, SensorReading reading) {
        return thresholdRepository.findByTenantIdAndShipmentId(tenantId, shipmentId)
                .map(threshold -> {
                    String violation = threshold.checkViolation(
                            reading.getTemperatureCelsius(), reading.getHumidityPercent());
                    if (violation != null) {
                        shipmentService.addTrackingEvent(shipmentId, new ShipmentDtos.AddTrackingEventRequest(
                                "EXCEPTION", null, "Sensor threshold violation: " + violation, reading.getRecordedAt()));
                        return true;
                    }
                    return false;
                })
                .orElse(false); // no threshold configured — nothing to violate
    }

    @Transactional
    public ThresholdResponse setThreshold(UUID shipmentId, SetThresholdRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        shipmentService.get(shipmentId);

        SensorThreshold threshold = new SensorThreshold(shipmentId, tenantId,
                request.minTemperatureCelsius(), request.maxTemperatureCelsius(),
                request.minHumidityPercent(), request.maxHumidityPercent());
        return ThresholdResponse.from(thresholdRepository.save(threshold));
    }

    @Transactional(readOnly = true)
    public Page<ReadingResponse> history(UUID shipmentId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        shipmentService.get(shipmentId);

        var threshold = thresholdRepository.findByTenantIdAndShipmentId(tenantId, shipmentId).orElse(null);
        return readingRepository.findAllByTenantIdAndShipmentIdOrderByRecordedAtDesc(tenantId, shipmentId, pageable)
                .map(r -> ReadingResponse.from(r, threshold != null &&
                        threshold.checkViolation(r.getTemperatureCelsius(), r.getHumidityPercent()) != null));
    }
}

