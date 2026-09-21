package com.logiplatform.service;

import com.logiplatform.model.VehicleGpsPosition;
import com.logiplatform.repository.VehicleGpsPositionRepository;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;
import static com.logiplatform.dto.GpsDtos.*;



/**
 * Two storage layers, verified independently before being wired together (see
 * RLS_VERIFICATION.md): Postgres holds the durable, RLS-protected history for
 * analytics/audit/replay; Redis holds only the CURRENT position per vehicle for fast
 * "where is it right now" reads without hitting Postgres on every poll from a live
 * map UI.
 *
 * Redis is a cache here, not a source of truth: if it's ever unavailable or a key
 * expired/evicted, latest() falls back to the most recent Postgres row rather than
 * failing — the live map gets briefly slower, not broken.
 */
@Service
public class GpsTrackingService {

    private static final String REDIS_KEY_PREFIX = "vehicle:position:";

    private final VehicleGpsPositionRepository positionRepository;
    private final VehicleService vehicleService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FleetEventPublisher fleetEventPublisher;
    private final OperationsEventStreamService operationsEventStream;

    public GpsTrackingService(VehicleGpsPositionRepository positionRepository, VehicleService vehicleService,
                               StringRedisTemplate redisTemplate, ObjectMapper objectMapper, FleetEventPublisher fleetEventPublisher, OperationsEventStreamService operationsEventStream) {
        this.positionRepository = positionRepository;
        this.vehicleService = vehicleService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.fleetEventPublisher = fleetEventPublisher;
        this.operationsEventStream = operationsEventStream;
    }

    /** Matches the exact JSON shape verified directly against Redis before this was written. */
    private record CachedPosition(double lat, double lng, Double speedKmh, Double headingDegrees, Instant recordedAt) {}

    @Transactional
    public PositionResponse recordPosition(UUID vehicleId, RecordPositionRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        vehicleService.getOwned(vehicleId); // 404s if this vehicle isn't the caller's

        Instant recordedAt = request.recordedAt() != null ? request.recordedAt() : Instant.now();

        VehicleGpsPosition position;
        try {
            position = new VehicleGpsPosition(tenantId, vehicleId, request.latitude(), request.longitude(),
                    request.speedKmh(), request.headingDegrees(), recordedAt);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        positionRepository.save(position);
        try { fleetEventPublisher.publish(tenantId, vehicleId, position.getLatitude(), position.getLongitude(), position.getRecordedAt()); } catch (Exception ignored) { /* Kafka is an acceleration path; Postgres remains authoritative. */ }

        updateCache(vehicleId, position);
        operationsEventStream.publish(tenantId, "gps", java.util.Map.of("vehicleId", vehicleId, "latitude", position.getLatitude(), "longitude", position.getLongitude(), "recordedAt", position.getRecordedAt().toString()));
        return PositionResponse.from(position);
    }

    @Transactional(readOnly = true)
    public PositionResponse latest(UUID vehicleId) {
        vehicleService.getOwned(vehicleId); // enforces tenant ownership before touching Redis at all

        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + vehicleId);
        } catch (Exception e) {
            // Redis unreachable — this is exactly the case the class-level comment
            // promises to handle gracefully. Falling through to Postgres below, not
            // propagating this as a 500.
        }

        if (cached != null) {
            try {
                CachedPosition p = objectMapper.readValue(cached, CachedPosition.class);
                return new PositionResponse(vehicleId, p.lat(), p.lng(), p.speedKmh(), p.headingDegrees(), p.recordedAt());
            } catch (Exception e) {
                // Malformed cache entry — fall through to Postgres rather than error out.
            }
        }

        // Cache miss or Redis unavailable — fall back to the durable record.
        UUID tenantId = TenantContext.getTenantId();
        return positionRepository
                .findAllByTenantIdAndVehicleIdOrderByRecordedAtDesc(tenantId, vehicleId, PageRequest.of(0, 1))
                .stream().findFirst().map(PositionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No position recorded for this vehicle yet"));
    }

    @Transactional(readOnly = true)
    public Page<PositionResponse> history(UUID vehicleId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        vehicleService.getOwned(vehicleId);
        return positionRepository.findAllByTenantIdAndVehicleIdOrderByRecordedAtDesc(tenantId, vehicleId, pageable)
                .map(PositionResponse::from);
    }

    private void updateCache(UUID vehicleId, VehicleGpsPosition position) {
        try {
            CachedPosition cached = new CachedPosition(position.getLatitude(), position.getLongitude(),
                    position.getSpeedKmh(), position.getHeadingDegrees(), position.getRecordedAt());
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + vehicleId, objectMapper.writeValueAsString(cached));
        } catch (Exception e) {
            // Redis being unavailable must not fail the write — Postgres already has
            // the durable record; the live-map cache will just be stale until Redis
            // recovers or the next position update succeeds in refreshing it.
        }
    }
}

