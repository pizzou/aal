package com.logiplatform.model;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="iot_devices",uniqueConstraints=@UniqueConstraint(name="uk_iot_device",columnNames={"tenant_id","device_code"}))
public class IoTDevice {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="device_code",nullable=false) private String deviceCode; @Column(name="shipment_id") private UUID shipmentId; @Column(name="secret_hash",nullable=false) private String secretHash; @Column(name="enabled",nullable=false) private boolean enabled=true; @Column(name="last_seen_at") private Instant lastSeenAt;
 protected IoTDevice(){} public IoTDevice(UUID t,String code,UUID shipment,String hash){tenantId=t;deviceCode=code;shipmentId=shipment;secretHash=hash;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getDeviceCode(){return deviceCode;} public UUID getShipmentId(){return shipmentId;} public String getSecretHash(){return secretHash;} public boolean isEnabled(){return enabled;} public Instant getLastSeenAt(){return lastSeenAt;} public void seen(){lastSeenAt=Instant.now();}
}
