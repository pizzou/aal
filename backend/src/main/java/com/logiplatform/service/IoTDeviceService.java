package com.logiplatform.service;

import com.logiplatform.model.IoTDevice;
import com.logiplatform.repository.IoTDeviceRepository;
import com.logiplatform.dto.SensorDtos;


import com.logiplatform.tenancy.TenantContext; import org.springframework.stereotype.Service; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.*;
@Service public class IoTDeviceService {
 private final IoTDeviceRepository repo; private final SensorMonitoringService sensors; private final ShipmentService shipments;
 public IoTDeviceService(IoTDeviceRepository r,SensorMonitoringService s,ShipmentService sh){repo=r;sensors=s;shipments=sh;}
 public Map<String,Object> register(UUID shipmentId,String deviceCode,String secret){if(secret==null||secret.isBlank()||deviceCode==null||deviceCode.isBlank())throw new IllegalArgumentException("deviceCode and secret are required");shipments.get(shipmentId);String token=hash(secret);IoTDevice d=repo.save(new IoTDevice(TenantContext.getTenantId(),deviceCode,shipmentId,token));return Map.of("deviceId",d.getId(),"deviceCode",d.getDeviceCode(),"secret","return-to-device-once");}
 public SensorDtos.ReadingResponse ingest(String deviceCode,String secret,SensorDtos.RecordReadingRequest request){IoTDevice d=repo.findByTenantIdAndDeviceCode(TenantContext.getTenantId(),deviceCode).orElseThrow(()->new IllegalArgumentException("Unknown IoT device"));if(!d.isEnabled()||!MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.UTF_8),d.getSecretHash().getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException("Invalid or disabled IoT device");d.seen();repo.save(d);return sensors.recordReading(d.getShipmentId(),request);}
 private static String hash(String s){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
