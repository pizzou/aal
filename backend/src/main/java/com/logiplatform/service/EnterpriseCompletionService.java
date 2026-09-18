package com.logiplatform.service;

import com.logiplatform.dto.EnterpriseCompletionDtos.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class EnterpriseCompletionService {
    private final JdbcTemplate db;
    private final String jwtSecret;
    private final SecureRandom random = new SecureRandom();

    public EnterpriseCompletionService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                       @org.springframework.beans.factory.annotation.Value("${jwt.secret}") String jwtSecret) {
        this.db = db;
        this.jwtSecret = jwtSecret;
    }

    @Transactional
    public MfaSetupResponse setupMfa(UUID userId) {
        String secret = base32(randomBytes(20));
        String enc = encrypt(secret);
        db.update("UPDATE users SET mfa_secret_enc=?, mfa_enabled=false, mfa_verified_at=NULL WHERE id=? AND tenant_id=?",
                enc, userId, TenantContext.getTenantId());
        String uri = "otpauth://totp/Africa%20Logistic%20Aviation?secret=" + secret + "&issuer=AAL";
        return new MfaSetupResponse(secret, uri, false);
    }

    @Transactional
    public MfaStatusResponse verifyMfa(UUID userId, String code) {
        String enc = db.query("SELECT mfa_secret_enc FROM users WHERE id=? AND tenant_id=?", rs -> rs.next()?rs.getString(1):null,
                userId, TenantContext.getTenantId());
        if (enc == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MFA is not configured");
        String secret = decrypt(enc);
        if (!totp(secret, Instant.now()).equals(normalizeCode(code)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid authentication code");
        Instant now = Instant.now();
        db.update("UPDATE users SET mfa_enabled=true, mfa_verified_at=? WHERE id=? AND tenant_id=?", now, userId, TenantContext.getTenantId());
        return new MfaStatusResponse(true, true, now);
    }

    @Transactional
    public void disableMfa(UUID userId) {
        db.update("UPDATE users SET mfa_enabled=false, mfa_secret_enc=NULL, mfa_verified_at=NULL WHERE id=? AND tenant_id=?",
                userId, TenantContext.getTenantId());
    }

    public MfaStatusResponse mfaStatus(UUID userId) {
        return db.query("SELECT mfa_secret_enc,mfa_enabled,mfa_verified_at FROM users WHERE id=? AND tenant_id=?",
                rs -> rs.next()?new MfaStatusResponse(rs.getString(1)!=null,rs.getBoolean(2),rs.getTimestamp(3)==null?null:rs.getTimestamp(3).toInstant()):null,
                userId,TenantContext.getTenantId());
    }

    @Transactional
    public FxRateResponse upsertFx(FxRateRequest r) {
        UUID t=TenantContext.getTenantId();
        db.update("INSERT INTO finance_fx_rates(tenant_id,rate_date,base_currency,quote_currency,rate,source) VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,rate_date,base_currency,quote_currency) DO UPDATE SET rate=EXCLUDED.rate,source=EXCLUDED.source",
                t,r.rateDate(),r.baseCurrency().toUpperCase(),r.quoteCurrency().toUpperCase(),r.rate(),r.source());
        return db.queryForObject("SELECT id,rate_date,base_currency,quote_currency,rate,source FROM finance_fx_rates WHERE tenant_id=? AND rate_date=? AND base_currency=? AND quote_currency=?",
                (rs,n)->new FxRateResponse(UUID.fromString(rs.getString(1)),rs.getDate(2).toLocalDate(),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getString(6)),
                t,r.rateDate(),r.baseCurrency().toUpperCase(),r.quoteCurrency().toUpperCase());
    }

    public List<FxRateResponse> fxRates(LocalDate from, LocalDate to) {
        return db.query("SELECT id,rate_date,base_currency,quote_currency,rate,source FROM finance_fx_rates WHERE tenant_id=? AND rate_date BETWEEN ? AND ? ORDER BY rate_date DESC,base_currency,quote_currency",
                (rs,n)->new FxRateResponse(UUID.fromString(rs.getString(1)),rs.getDate(2).toLocalDate(),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getString(6)),TenantContext.getTenantId(),from,to);
    }

    @Transactional public PeriodResponse openPeriod(PeriodRequest r) {
        if(r.periodEnd().isBefore(r.periodStart())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid financial period");
        UUID t=TenantContext.getTenantId();
        db.update("INSERT INTO finance_periods(tenant_id,period_start,period_end,status,notes) VALUES(?,?,?,?,?)",t,r.periodStart(),r.periodEnd(),"OPEN",r.notes());
        return period(t,r.periodStart(),r.periodEnd());
    }
    @Transactional public PeriodResponse closePeriod(UUID id) {
        UUID t=TenantContext.getTenantId();
        int n=db.update("UPDATE finance_periods SET status='CLOSED',closed_at=now(),closed_by=? WHERE id=? AND tenant_id=? AND status='OPEN'",currentUser(),id,t);
        if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Financial period not found or already closed");
        return db.queryForObject("SELECT id,period_start,period_end,status,closed_at,notes FROM finance_periods WHERE id=? AND tenant_id=?",this::periodRow,id,t);
    }
    public List<PeriodResponse> periods(){return db.query("SELECT id,period_start,period_end,status,closed_at,notes FROM finance_periods WHERE tenant_id=? ORDER BY period_start DESC",this::periodRow,TenantContext.getTenantId());}

    @Transactional public AdjustmentResponse createAdjustment(AdjustmentRequest r){
        UUID t=TenantContext.getTenantId();
        db.update("INSERT INTO finance_adjustments(tenant_id,adjustment_no,adjustment_type,invoice_id,shipment_id,amount,currency,reason,status,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                t,r.adjustmentNo(),r.adjustmentType().toUpperCase(),r.invoiceId(),r.shipmentId(),r.amount(),r.currency().toUpperCase(),r.reason(),"DRAFT",currentUser());
        return db.queryForObject("SELECT id,adjustment_no,adjustment_type,invoice_id,shipment_id,amount,currency,reason,status FROM finance_adjustments WHERE tenant_id=? AND adjustment_no=?",this::adjustmentRow,t,r.adjustmentNo());
    }
    @Transactional
    public AdjustmentResponse approveAdjustment(UUID id) {
        int n=db.update("UPDATE finance_adjustments SET status='APPROVED',approved_at=now() WHERE id=? AND tenant_id=? AND status='DRAFT'",id,TenantContext.getTenantId());
        if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Adjustment not found or already processed");
        return db.queryForObject("SELECT id,adjustment_no,adjustment_type,invoice_id,shipment_id,amount,currency,reason,status FROM finance_adjustments WHERE id=? AND tenant_id=?",this::adjustmentRow,id,TenantContext.getTenantId());
    }

    public List<AdjustmentResponse> adjustments(){return db.query("SELECT id,adjustment_no,adjustment_type,invoice_id,shipment_id,amount,currency,reason,status FROM finance_adjustments WHERE tenant_id=? ORDER BY created_at DESC",this::adjustmentRow,TenantContext.getTenantId());}

    @Transactional public GeofenceResponse createGeofence(GeofenceRequest r){UUID t=TenantContext.getTenantId(); db.update("INSERT INTO aal_geofences(tenant_id,name,latitude,longitude,radius_m) VALUES(?,?,?,?,?)",t,r.name(),r.latitude(),r.longitude(),r.radiusM()); return db.queryForObject("SELECT id,name,latitude,longitude,radius_m,active FROM aal_geofences WHERE tenant_id=? AND name=?",this::geofenceRow,t,r.name());}
    public List<GeofenceResponse> geofences(){return db.query("SELECT id,name,latitude,longitude,radius_m,active FROM aal_geofences WHERE tenant_id=? ORDER BY name",this::geofenceRow,TenantContext.getTenantId());}
    public List<GeofenceEvaluation> evaluateGeofences(double lat,double lon){return db.query("SELECT name,latitude,longitude,radius_m FROM aal_geofences WHERE tenant_id=? AND active=true",(rs,n)->{double d=distance(lat,lon,rs.getDouble(2),rs.getDouble(3)); return new GeofenceEvaluation(d,d<=rs.getInt(4),rs.getString(1));},TenantContext.getTenantId());}

    @Transactional
    public void updateLeg(UUID legId, LegUpdateRequest r) {
        assertLeg(legId);
        db.update("UPDATE transport_legs SET carrier_reference=COALESCE(?,carrier_reference), equipment_reference=COALESCE(?,equipment_reference), actual_departure=COALESCE(?,actual_departure), actual_arrival=COALESCE(?,actual_arrival), status=COALESCE(?,status), notes=COALESCE(?,notes) WHERE id=? AND tenant_id=?", r.carrierReference(), r.equipmentReference(), r.actualDeparture(), r.actualArrival(), r.status(), r.notes(), legId, TenantContext.getTenantId());
    }

    @Transactional public void addLegMilestone(UUID legId,LegMilestoneRequest r){assertLeg(legId);db.update("INSERT INTO transport_leg_milestones(tenant_id,leg_id,milestone_type,location,planned_at,actual_at,status,notes) VALUES(?,?,?,?,?,?,?,?)",TenantContext.getTenantId(),legId,r.milestoneType(),r.location(),r.plannedAt(),r.actualAt(),r.status()==null?"PLANNED":r.status(),r.notes());}
    @Transactional public void addLegDocument(UUID legId,LegDocumentRequest r){assertLeg(legId);db.update("INSERT INTO transport_leg_documents(tenant_id,leg_id,document_type,document_uri,customer_visible) VALUES(?,?,?,?,?)",TenantContext.getTenantId(),legId,r.documentType(),r.documentUri(),r.customerVisible());}
    @Transactional public void addLegCost(UUID legId,LegCostRequest r){assertLeg(legId);db.update("INSERT INTO transport_leg_costs(tenant_id,leg_id,description,amount,currency,supplier) VALUES(?,?,?,?,?,?)",TenantContext.getTenantId(),legId,r.description(),r.amount(),r.currency().toUpperCase(),r.supplier());}

    @Transactional public IntegrationResponse registerIntegration(IntegrationRequest r){UUID t=TenantContext.getTenantId();db.update("INSERT INTO integration_registry(tenant_id,code,display_name,protocol,base_url,enabled) VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,code) DO UPDATE SET display_name=EXCLUDED.display_name,protocol=EXCLUDED.protocol,base_url=EXCLUDED.base_url,enabled=EXCLUDED.enabled,updated_at=now()",t,r.code().toUpperCase(),r.displayName(),r.protocol(),r.baseUrl(),r.enabled());return integration(t,r.code().toUpperCase());}
    public List<IntegrationResponse> integrations(){return db.query("SELECT id,code,display_name,protocol,base_url,enabled,health_status,last_success_at,last_failure_at,last_error FROM integration_registry WHERE tenant_id=? ORDER BY display_name",this::integrationRow,TenantContext.getTenantId());}
    @Transactional public void recordIntegrationAttempt(String code,String operation,String key,String status,Integer responseCode,String error){db.update("INSERT INTO integration_attempts(tenant_id,integration_code,operation,idempotency_key,status,response_code,error_detail) VALUES(?,?,?,?,?,?,?)",TenantContext.getTenantId(),code.toUpperCase(),operation,key,status,responseCode,error);}

    @Transactional
    public Map<String,Object> rotateTrackingToken(UUID shipmentId, Instant expiresAt) {
        UUID token=UUID.randomUUID();
        int n=db.update("UPDATE shipments SET tracking_token=?, tracking_expires_at=?, tracking_revoked=false, updated_at=now() WHERE id=? AND tenant_id=?",token,expiresAt,shipmentId,TenantContext.getTenantId());
        if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found");
        return Map.of("shipmentId",shipmentId,"trackingToken",token,"expiresAt",expiresAt==null?"":expiresAt.toString());
    }
    @Transactional public void revokeTrackingToken(UUID shipmentId){int n=db.update("UPDATE shipments SET tracking_revoked=true, updated_at=now() WHERE id=? AND tenant_id=?",shipmentId,TenantContext.getTenantId());if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found");}

    @Transactional public void queueNotification(NotificationQueueRequest r){db.update("INSERT INTO notification_queue(tenant_id,shipment_id,channel,recipient,event_type,subject,body) VALUES(?,?,?,?,?,?,?)",TenantContext.getTenantId(),r.shipmentId(),r.channel().toUpperCase(),r.recipient(),r.eventType().toUpperCase(),r.subject(),r.body());}
    @Transactional
    public void retryNotification(UUID id) {
        int n=db.update("UPDATE notification_queue SET status='QUEUED',next_attempt_at=now(),last_error=NULL WHERE id=? AND tenant_id=? AND status IN ('FAILED','QUEUED')",id,TenantContext.getTenantId());
        if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification queue item not found");
    }

    public List<Map<String,Object>> queuedNotifications(){return db.queryForList("SELECT id,shipment_id,channel,recipient,event_type,subject,status,attempts,next_attempt_at,last_error,created_at FROM notification_queue WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",TenantContext.getTenantId());}

    private void assertLeg(UUID id){Integer n=db.queryForObject("SELECT count(*) FROM transport_legs WHERE id=? AND tenant_id=?",Integer.class,id,TenantContext.getTenantId());if(n==null||n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Transport leg not found");}
    private UUID currentUser(){try{Object p=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal(); if(p instanceof com.logiplatform.security.TenantPrincipal tp) return tp.userId(); return null;}catch(Exception e){return null;}}
    private PeriodResponse period(UUID t,LocalDate s,LocalDate e){return db.queryForObject("SELECT id,period_start,period_end,status,closed_at,notes FROM finance_periods WHERE tenant_id=? AND period_start=? AND period_end=?",this::periodRow,t,s,e);}
    private PeriodResponse periodRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new PeriodResponse(UUID.fromString(rs.getString(1)),rs.getDate(2).toLocalDate(),rs.getDate(3).toLocalDate(),rs.getString(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getString(6));}
    private AdjustmentResponse adjustmentRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new AdjustmentResponse(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getObject(4)==null?null:UUID.fromString(rs.getString(4)),rs.getObject(5)==null?null:UUID.fromString(rs.getString(5)),rs.getBigDecimal(6),rs.getString(7),rs.getString(8),rs.getString(9));}
    private GeofenceResponse geofenceRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new GeofenceResponse(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getDouble(3),rs.getDouble(4),rs.getInt(5),rs.getBoolean(6));}
    private IntegrationResponse integrationRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new IntegrationResponse(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBoolean(6),rs.getString(7),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant(),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant(),rs.getString(10));}
    private IntegrationResponse integration(UUID t,String c){return db.queryForObject("SELECT id,code,display_name,protocol,base_url,enabled,health_status,last_success_at,last_failure_at,last_error FROM integration_registry WHERE tenant_id=? AND code=?",this::integrationRow,t,c);}

    private byte[] randomBytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private String normalizeCode(String s){return s==null?"":s.replaceAll("\\s+","").trim();}
    private String base32(byte[] bytes){final char[] a="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();StringBuilder out=new StringBuilder();int buffer=0,bits=0;for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){out.append(a[(buffer>>(bits-5))&31]);bits-=5;}}if(bits>0)out.append(a[(buffer<<(5-bits))&31]);return out.toString();}
    private byte[] base32decode(String s){String x=s.toUpperCase(Locale.ROOT).replace("=","");ByteArrayOutputStreamEx out=new ByteArrayOutputStreamEx();int buffer=0,bits=0;for(char c:x.toCharArray()){int v="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);if(v<0)throw new IllegalArgumentException("Invalid base32 secret");buffer=(buffer<<5)|v;bits+=5;if(bits>=8){out.write((buffer>>(bits-8))&255);bits-=8;}}return out.toByteArray();}
    private String totp(String secret,Instant now){try{long counter=now.getEpochSecond()/30;byte[] msg=ByteBuffer.allocate(8).putLong(counter).array();javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(new javax.crypto.spec.SecretKeySpec(base32decode(secret),"HmacSHA1"));byte[] h=mac.doFinal(msg);int o=h[h.length-1]&15;int bin=((h[o]&127)<<24)|((h[o+1]&255)<<16)|((h[o+2]&255)<<8)|(h[o+3]&255);return String.format(Locale.ROOT,"%06d",bin%1000000);}catch(Exception e){throw new IllegalStateException("Unable to calculate TOTP",e);}}
    private String encrypt(String plain){try{byte[] iv=randomBytes(12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keyBytes(),"AES"),new GCMParameterSpec(128,iv));byte[] ct=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(12+ct.length).put(iv).put(ct).array());}catch(Exception e){throw new IllegalStateException(e);}}
    private String decrypt(String enc){try{byte[] all=Base64.getUrlDecoder().decode(enc);byte[] iv=Arrays.copyOfRange(all,0,12),ct=Arrays.copyOfRange(all,12,all.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keyBytes(),"AES"),new GCMParameterSpec(128,iv));return new String(c.doFinal(ct),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Unable to decrypt MFA secret",e);}}
    private byte[] keyBytes(){try{return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(jwtSecret.getBytes(StandardCharsets.UTF_8)),16);}catch(Exception e){throw new IllegalStateException(e);}}
    private double distance(double a,double b,double c,double d){double r=6371000.0;double p1=Math.toRadians(a),p2=Math.toRadians(c),dp=Math.toRadians(c-a),dl=Math.toRadians(d-b);double q=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);return 2*r*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));}
    private static final class ByteArrayOutputStreamEx{private byte[] b=new byte[64];private int n;void write(int x){if(n==b.length)b=Arrays.copyOf(b,n*2);b[n++]=(byte)x;}byte[]toByteArray(){return Arrays.copyOf(b,n);}}
}
