package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class AirCargoIntegrationAlertService {
    private final JdbcTemplate db; private final ShipmentRepository shipments; private final NotificationService notifications; private final UUID tenantId; private final String operationsEmail; private final long staleMinutes;
    public AirCargoIntegrationAlertService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,ShipmentRepository shipments,NotificationService notifications,@Value("${app.single-tenant.id}") String tenant,@Value("${aircargo.operations-notification-email:}") String operationsEmail,@Value("${aircargo.eta.stale-after-minutes:180}") long staleMinutes){this.db=db;this.shipments=shipments;this.notifications=notifications;this.tenantId=UUID.fromString(tenant);this.operationsEmail=operationsEmail==null?"":operationsEmail.trim();this.staleMinutes=staleMinutes;}
    @Scheduled(fixedDelayString="${aircargo.alerts.interval-ms:900000}")
    public void scan(){if(operationsEmail.isBlank())return;TenantContext.setTenantId(tenantId);try{scanStaleEta();scanProviderFailures();}finally{TenantContext.clear();}}
    private void scanStaleEta(){Instant cutoff=Instant.now().minus(Duration.ofMinutes(staleMinutes));String sql="SELECT s.id,s.reference_code,s.flight_number FROM shipments s WHERE s.tenant_id=? AND s.transport_mode='AIR' AND s.status IN ('BOOKED','IN_TRANSIT') AND s.flight_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM shipment_eta_history h WHERE h.tenant_id=s.tenant_id AND h.shipment_id=s.id AND h.observed_at>=?)";db.query(sql,rs->{UUID id=UUID.fromString(rs.getString(1));String ref=rs.getString(2);String flight=rs.getString(3);String subject=ref==null?id.toString():ref;String message="No fresh ETA/flight-status update has been received for flight "+flight+" for more than "+staleMinutes+" minutes.";createAlert("STALE_ETA",subject,message);notifications.notify(id,operationsEmail,"AAL stale ETA alert: "+subject,message);},tenantId,cutoff);}
    private void scanProviderFailures(){Integer failed=db.queryForObject("SELECT COUNT(*) FROM integration_attempts WHERE tenant_id=? AND created_at>=? AND status='FAILED'",Integer.class,tenantId,Instant.now().minus(Duration.ofMinutes(15)));if(failed!=null&&failed>=3){String msg="Airline integration has recorded "+failed+" failed outbound attempts in the last 15 minutes.";createAlert("PROVIDER_OUTAGE","ACTIVE",msg);}}
    private void createAlert(String type,String subject,String message){Integer count=db.queryForObject("SELECT COUNT(*) FROM airline_integration_alerts WHERE tenant_id=? AND alert_type=? AND subject_ref=? AND status='OPEN'",Integer.class,tenantId,type,subject);if(count!=null&&count>0)return;db.update("INSERT INTO airline_integration_alerts(id,tenant_id,alert_type,subject_ref,message) VALUES(?,?,?,?,?)",UUID.randomUUID(),tenantId,type,subject,message);}
}
