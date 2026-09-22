package com.logiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.logiplatform.model.VehicleGpsPosition;
import com.logiplatform.repository.VehicleGpsPositionRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static com.logiplatform.dto.GpsDtos.*;

@Service
public class GpsTrackingService {
    private static final String REDIS_KEY_PREFIX="vehicle:position:";
    private final VehicleGpsPositionRepository positionRepository; private final VehicleService vehicleService;
    private final StringRedisTemplate redisTemplate; private final ObjectMapper objectMapper;
    private final FleetEventPublisher fleetEventPublisher; private final OperationsEventStreamService operationsEventStream;
    public GpsTrackingService(VehicleGpsPositionRepository p, VehicleService v, StringRedisTemplate r, ObjectMapper o, FleetEventPublisher f, OperationsEventStreamService e){positionRepository=p;vehicleService=v;redisTemplate=r;objectMapper=o;fleetEventPublisher=f;operationsEventStream=e;}
    private record CachedPosition(double lat,double lng,Double speedKmh,Double headingDegrees,Instant recordedAt,String source,String deviceId,Double accuracyMeters,Double batteryPercent){}

    @Transactional public PositionResponse recordPosition(UUID vehicleId, RecordPositionRequest request){
        return recordPosition(vehicleId,request,"MANUAL",null,null,null);
    }
    @Transactional public PositionResponse recordPosition(UUID vehicleId, RecordPositionRequest request,String source,String deviceId,Double accuracyMeters,Double batteryPercent){
        UUID tenantId=TenantContext.getTenantId(); vehicleService.getOwned(vehicleId);
        Instant recordedAt=request.recordedAt()!=null?request.recordedAt():Instant.now();
        VehicleGpsPosition position;
        try{position=new VehicleGpsPosition(tenantId,vehicleId,request.latitude(),request.longitude(),request.speedKmh(),request.headingDegrees(),recordedAt,source,deviceId,accuracyMeters,batteryPercent);}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        positionRepository.save(position);
        try{fleetEventPublisher.publish(tenantId,vehicleId,position.getLatitude(),position.getLongitude(),position.getRecordedAt());}catch(Exception ignored){}
        updateCache(vehicleId,position);
        operationsEventStream.publish(tenantId,"gps",java.util.Map.of("vehicleId",vehicleId,"latitude",position.getLatitude(),"longitude",position.getLongitude(),"recordedAt",position.getRecordedAt().toString(),"source",position.getSource()));
        return PositionResponse.from(position);
    }
    @Transactional
    public PositionResponse recordIngestedPosition(UUID vehicleId, double latitude, double longitude, Double speedKmh, Double headingDegrees, Instant recordedAt, String source, String deviceId, Double accuracyMeters, Double batteryPercent) {
        UUID tenantId = TenantContext.getTenantId();
        vehicleService.getOwned(vehicleId);
        VehicleGpsPosition position = new VehicleGpsPosition(tenantId, vehicleId, latitude, longitude, speedKmh, headingDegrees, recordedAt, source, deviceId, accuracyMeters, batteryPercent);
        positionRepository.save(position);
        updateCache(vehicleId, position);
        try {
            operationsEventStream.publish(tenantId, "gps", java.util.Map.of("vehicleId", vehicleId, "latitude", latitude, "longitude", longitude, "recordedAt", recordedAt.toString(), "source", position.getSource()));
        } catch (Exception ignored) { }
        return PositionResponse.from(position);
    }

    @Transactional(readOnly=true) public PositionResponse latest(UUID vehicleId){
        vehicleService.getOwned(vehicleId); UUID tenantId=TenantContext.getTenantId();
        try{String cached=redisTemplate.opsForValue().get(REDIS_KEY_PREFIX+vehicleId); if(cached!=null){CachedPosition p=objectMapper.readValue(cached,CachedPosition.class);return new PositionResponse(vehicleId,p.lat(),p.lng(),p.speedKmh(),p.headingDegrees(),p.recordedAt(),p.source(),p.deviceId(),p.accuracyMeters(),p.batteryPercent());}}catch(Exception ignored){}
        return positionRepository.findAllByTenantIdAndVehicleIdOrderByRecordedAtDesc(tenantId,vehicleId,PageRequest.of(0,1)).stream().findFirst().map(PositionResponse::from).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No position recorded for this vehicle yet"));
    }
    @Transactional(readOnly=true) public GpsStatusResponse status(UUID vehicleId){
        vehicleService.getOwned(vehicleId); Instant checked=Instant.now();
        try{PositionResponse p=latest(vehicleId); long age=Math.max(0,Duration.between(p.recordedAt(),checked).getSeconds()); boolean stale=age>120; return new GpsStatusResponse(vehicleId,p,p.source(),stale?"STALE":"LIVE",stale,age,checked);}catch(ResponseStatusException e) { if(e.getStatusCode()==HttpStatus.NOT_FOUND) return new GpsStatusResponse(vehicleId,null,"NONE","NO_DATA",true,-1,checked); throw e;}
    }
    @Transactional(readOnly=true) public Page<PositionResponse> history(UUID vehicleId,Pageable pageable){UUID tenantId=TenantContext.getTenantId();vehicleService.getOwned(vehicleId);return positionRepository.findAllByTenantIdAndVehicleIdOrderByRecordedAtDesc(tenantId,vehicleId,pageable).map(PositionResponse::from);}
    private void updateCache(UUID vehicleId,VehicleGpsPosition p){try{CachedPosition c=new CachedPosition(p.getLatitude(),p.getLongitude(),p.getSpeedKmh(),p.getHeadingDegrees(),p.getRecordedAt(),p.getSource(),p.getDeviceId(),p.getAccuracyMeters(),p.getBatteryPercent());redisTemplate.opsForValue().set(REDIS_KEY_PREFIX+vehicleId,objectMapper.writeValueAsString(c));}catch(Exception ignored){}}
}
