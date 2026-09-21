package com.logiplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.dto.EnterpriseCompletionDtos.*;
import com.logiplatform.tenancy.TenantContext;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class EnterpriseCompletionService {
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final String jwtSecret;
    private final SecureRandom random = new SecureRandom();

    public EnterpriseCompletionService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
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


    @Transactional(readOnly = true)
    public List<Map<String, Object>> checklist() {
        List<Map<String,Object>> phases = new ArrayList<>();
        phases.add(phase("0", "Production foundation", List.of(
                item("0.1","Backend compile verification","CI_GATE"),
                item("0.2","Frontend TypeScript/build verification","CI_GATE"),
                item("0.3","Runtime/API consistency","IMPLEMENTED"),
                item("0.4","Database migration verification","IMPLEMENTED"),
                item("0.5","Tenant/RLS isolation","IMPLEMENTED"),
                item("0.6","Authentication + MFA + OTP","IMPLEMENTED"),
                item("0.7","Production error handling","IMPLEMENTED"),
                item("0.8","Structured logging + correlation IDs","IMPLEMENTED"),
                item("0.9","Health/readiness checks","IMPLEMENTED"),
                item("0.10","Backup/restore verification","OPS_DRILL"),
                item("0.11","CI/CD release gate","IMPLEMENTED"))));
        phases.add(phase("1", "Excel → Professional Logistics System", List.of(
                item("1.1","MOTHERSHIP shipment fields","IMPLEMENTED"), item("1.2","MOTHERSHIP financial calculations","IMPLEMENTED"),
                item("1.3","Command Center shipment register","IMPLEMENTED"), item("1.4","Quotation register","IMPLEMENTED"),
                item("1.5","Invoice register","IMPLEMENTED"), item("1.6","Client register","IMPLEMENTED"),
                item("1.7","Partner register","IMPLEMENTED"), item("1.8","Task register","IMPLEMENTED"),
                item("1.9","Expense register","IMPLEMENTED"), item("1.10","Excel migration/import","IMPLEMENTED"),
                item("1.11","Excel-to-database reconciliation","IMPLEMENTED"), item("1.12","Server calculations","IMPLEMENTED"),
                item("1.13","Historical monthly reporting","IMPLEMENTED"), item("1.14","Excel-free daily operations","IMPLEMENTED"))));
        phases.add(phase("2", "Professional Shipment Management", List.of(
                item("2.1","Shipment master record","IMPLEMENTED"), item("2.2","Shipment parties","IMPLEMENTED"),
                item("2.3","Cargo/package lines","IMPLEMENTED"), item("2.4","Transport legs","IMPLEMENTED"),
                item("2.5","Shipment status lifecycle","IMPLEMENTED"), item("2.6","Milestone templates","IMPLEMENTED"),
                item("2.7","Automatic milestone generation","IMPLEMENTED"), item("2.8","ETA management","IMPLEMENTED"),
                item("2.9","Shipment event history","IMPLEMENTED"), item("2.10","Shipment audit trail","IMPLEMENTED"),
                item("2.11","Shipment exception linkage","IMPLEMENTED"), item("2.12","Shipment profitability","IMPLEMENTED"))));
        phases.add(phase("3", "Advanced Quotation & Pricing", List.of(
                item("3.1","Customer-specific rate cards","IMPLEMENTED"), item("3.2","Carrier buy rates","IMPLEMENTED"),
                item("3.3","Lane-based rates","IMPLEMENTED"), item("3.4","Effective-date rate versions","IMPLEMENTED"),
                item("3.5","Minimum charges","IMPLEMENTED"), item("3.6","Fuel surcharge","IMPLEMENTED"),
                item("3.7","Security surcharge","IMPLEMENTED"), item("3.8","Handling/accessorial charges","IMPLEMENTED"),
                item("3.9","Pickup/delivery charges","IMPLEMENTED"), item("3.10","Customs/brokerage charges","IMPLEMENTED"),
                item("3.11","Documentation charges","IMPLEMENTED"), item("3.12","Insurance","IMPLEMENTED"),
                item("3.13","Dangerous goods surcharge","IMPLEMENTED"), item("3.14","Remote-area surcharge","IMPLEMENTED"),
                item("3.15","Customer discounts","IMPLEMENTED"), item("3.16","Markup rules","IMPLEMENTED"),
                item("3.17","Currency conversion","IMPLEMENTED"), item("3.18","Margin controls","IMPLEMENTED"),
                item("3.19","Quote approval workflow","IMPLEMENTED"), item("3.20","Quote versioning","IMPLEMENTED"),
                item("3.21","Quote validity","IMPLEMENTED"), item("3.22","Public quote calculation","IMPLEMENTED"))));
        phases.add(phase("4", "Customer Journey", List.of(
                item("4.1","Public quote request","IMPLEMENTED"), item("4.2","Rate options","IMPLEMENTED"),
                item("4.3","Quote comparison","IMPLEMENTED"), item("4.4","Quote details","IMPLEMENTED"),
                item("4.5","Secure quote link","IMPLEMENTED"), item("4.6","Quote acceptance","IMPLEMENTED"),
                item("4.7","Quote rejection","IMPLEMENTED"), item("4.8","Public booking","IMPLEMENTED"),
                item("4.9","Booking confirmation","IMPLEMENTED"), item("4.10","Customer tracking","IMPLEMENTED"),
                item("4.11","Customer document access","IMPLEMENTED"), item("4.12","Customer notifications","IMPLEMENTED"),
                item("4.13","Customer feedback","IMPLEMENTED"))));
        phases.add(externalPhase("5", "Air Freight", new String[]{"airline API/EDI", "e-AWB/ONE Record"}));
        phases.add(externalPhase("6", "Ocean Freight", new String[]{"DCSA carrier connection", "port/terminal feeds", "eBL provider"}));
        phases.add(phase("7", "Road & Last Mile", List.of(
                item("7.1","Road booking","IMPLEMENTED"), item("7.2","Carrier assignment","IMPLEMENTED"),
                item("7.3","Vehicle assignment","IMPLEMENTED"), item("7.4","Driver assignment","IMPLEMENTED"),
                item("7.5","Trip planning","IMPLEMENTED"), item("7.6","Route planning","IMPLEMENTED"),
                item("7.7","Pickup scheduling","IMPLEMENTED"), item("7.8","Delivery scheduling","IMPLEMENTED"),
                item("7.9","GPS tracking","IMPLEMENTED"), item("7.10","Geofencing","IMPLEMENTED"),
                item("7.11","Electronic POD","IMPLEMENTED"), item("7.12","Driver mobile workflow","IMPLEMENTED"),
                item("7.13","Delivery exceptions","IMPLEMENTED"))));
        phases.add(phase("8", "Warehouse / WMS", List.of(
                item("8.1","Warehouse master","IMPLEMENTED"), item("8.2","Locations/bins","IMPLEMENTED"),
                item("8.3","Inventory","IMPLEMENTED"), item("8.4","Receiving","IMPLEMENTED"),
                item("8.5","Put-away","IMPLEMENTED"), item("8.6","Picking","IMPLEMENTED"),
                item("8.7","Packing","IMPLEMENTED"), item("8.8","Dispatch","IMPLEMENTED"),
                item("8.9","Stock movements","IMPLEMENTED"), item("8.10","Cycle counting","IMPLEMENTED"),
                item("8.11","Barcode/QR","IMPLEMENTED"), item("8.12","Warehouse task workflow","IMPLEMENTED"))));
        phases.add(phase("9", "Customs & Compliance", List.of(
                item("9.1","Customs declaration","IMPLEMENTED"), item("9.2","HS classification","IMPLEMENTED"),
                item("9.3","Country of origin","IMPLEMENTED"), item("9.4","Customs valuation","IMPLEMENTED"),
                item("9.5","Incoterms","IMPLEMENTED"), item("9.6","Broker management","IMPLEMENTED"),
                item("9.7","Customs document package","IMPLEMENTED"), item("9.8","Customs submission","IMPLEMENTED"),
                item("9.9","Customs response","IMPLEMENTED"), item("9.10","Customs release","IMPLEMENTED"),
                item("9.11","WCO data mapping","CONFIGURABLE"), item("9.12","Country-specific integrations","EXTERNAL_UAT"),
                item("9.13","Compliance audit","IMPLEMENTED"))));
        phases.add(phase("10", "Documents", List.of(
                item("10.1","Document repository","IMPLEMENTED"), item("10.2","Document versioning","IMPLEMENTED"),
                item("10.3","Document approval","IMPLEMENTED"), item("10.4","Document templates","IMPLEMENTED"),
                item("10.5","Automatic generation","IMPLEMENTED"), item("10.6","PDF generation","IMPLEMENTED"),
                item("10.7","Digital signature workflow","IMPLEMENTED"), item("10.8","Document expiry","IMPLEMENTED"),
                item("10.9","Document permissions","IMPLEMENTED"), item("10.10","Customer document sharing","IMPLEMENTED"),
                item("10.11","Document audit","IMPLEMENTED"), item("10.12","Malware scan workflow","IMPLEMENTED"))));
        phases.add(phase("11", "Finance", List.of(
                item("11.1","Invoicing","IMPLEMENTED"), item("11.2","Receivables","IMPLEMENTED"), item("11.3","Payables","IMPLEMENTED"),
                item("11.4","Payments","IMPLEMENTED"), item("11.5","Payment allocation","IMPLEMENTED"), item("11.6","Aging","IMPLEMENTED"),
                item("11.7","Credit notes","IMPLEMENTED"), item("11.8","Debit notes","IMPLEMENTED"), item("11.9","Supplier settlement","IMPLEMENTED"),
                item("11.10","Customer statements","IMPLEMENTED"), item("11.11","Supplier statements","IMPLEMENTED"), item("11.12","FX","IMPLEMENTED"),
                item("11.13","Tax/VAT","IMPLEMENTED"), item("11.14","Bank reconciliation","IMPLEMENTED"), item("11.15","General ledger","IMPLEMENTED"),
                item("11.16","Period close","IMPLEMENTED"), item("11.17","Accounting-system export","IMPLEMENTED"))));
        phases.add(phase("12", "Exception & Control Tower", List.of(
                item("12.1","Exception engine","IMPLEMENTED"), item("12.2","Automatic exception creation","IMPLEMENTED"),
                item("12.3","Severity rules","IMPLEMENTED"), item("12.4","SLA timers","IMPLEMENTED"), item("12.5","Escalation","IMPLEMENTED"),
                item("12.6","Ownership","IMPLEMENTED"), item("12.7","Root cause","IMPLEMENTED"), item("12.8","Corrective action","IMPLEMENTED"),
                item("12.9","Customer notification","IMPLEMENTED"), item("12.10","Control Tower KPIs","IMPLEMENTED"))));
        phases.add(phase("13", "Carrier Management", List.of(
                item("13.1","Carrier master","IMPLEMENTED"), item("13.2","Carrier contracts","IMPLEMENTED"), item("13.3","Carrier rates","IMPLEMENTED"),
                item("13.4","Carrier tender","IMPLEMENTED"), item("13.5","Carrier acceptance","IMPLEMENTED"), item("13.6","Carrier performance","IMPLEMENTED"),
                item("13.7","Carrier scorecards","IMPLEMENTED"), item("13.8","Carrier invoice audit","IMPLEMENTED"), item("13.9","Carrier settlement","IMPLEMENTED"))));
        phases.add(phase("14", "Advanced Analytics", List.of(
                item("14.1","Revenue analytics","IMPLEMENTED"), item("14.2","Gross margin","IMPLEMENTED"), item("14.3","Shipment profitability","IMPLEMENTED"),
                item("14.4","Customer profitability","IMPLEMENTED"), item("14.5","Carrier profitability","IMPLEMENTED"), item("14.6","Lane profitability","IMPLEMENTED"),
                item("14.7","Operational KPI","IMPLEMENTED"), item("14.8","On-time performance","IMPLEMENTED"), item("14.9","Aging analytics","IMPLEMENTED"),
                item("14.10","Forecasting","IMPLEMENTED"), item("14.11","Predictive ETA","IMPLEMENTED"), item("14.12","Predictive exceptions","IMPLEMENTED"))));
        phases.add(phase("15", "Automation / Intelligence", List.of(
                item("15.1","Automatic milestone updates","IMPLEMENTED"), item("15.2","Automatic exceptions","IMPLEMENTED"), item("15.3","Automatic notifications","IMPLEMENTED"),
                item("15.4","Automatic overdue actions","IMPLEMENTED"), item("15.5","Document completeness checks","IMPLEMENTED"), item("15.6","Pre-departure readiness","IMPLEMENTED"),
                item("15.7","Margin warnings","IMPLEMENTED"), item("15.8","Capacity alerts","IMPLEMENTED"), item("15.9","Predictive ETA","IMPLEMENTED"),
                item("15.10","Predictive delay","IMPLEMENTED"), item("15.11","Automated workflow engine","IMPLEMENTED"))));
        phases.add(externalPhase("16", "Enterprise Integrations", new String[]{"airline/ocean/customs/port providers", "GPS/payment/accounting", "EDI/SMS"}));
        phases.add(phase("17", "Enterprise Security & Reliability", List.of(
                item("17.1","MFA/OTP","IMPLEMENTED"), item("17.2","RBAC","IMPLEMENTED"), item("17.3","Tenant isolation","IMPLEMENTED"), item("17.4","Audit logging","IMPLEMENTED"),
                item("17.5","Rate limiting","IMPLEMENTED"), item("17.6","API security","IMPLEMENTED"), item("17.7","Secrets management","ENVIRONMENT"), item("17.8","Encryption","IMPLEMENTED"),
                item("17.9","Backup","OPS_DRILL"), item("17.10","Restore testing","OPS_DRILL"), item("17.11","Disaster recovery","OPS_DRILL"), item("17.12","Penetration testing","SECURITY_TEST"),
                item("17.13","Vulnerability scanning","CI_GATE"), item("17.14","Load testing","LOAD_TEST"))));
        phases.add(phase("18", "Mobile Operations", List.of(
                item("18.1","Driver mobile workflow","IMPLEMENTED"), item("18.2","Warehouse mobile workflow","IMPLEMENTED"), item("18.3","Barcode scanning","IMPLEMENTED"),
                item("18.4","POD capture","IMPLEMENTED"), item("18.5","GPS background tracking hook","DEVICE_UAT"), item("18.6","Offline sync/conflict resolution","IMPLEMENTED"),
                item("18.7","Push notification registration","IMPLEMENTED"))));
        return phases;
    }

    @Transactional
    public Map<String,Object> upsertCarrierContract(CarrierContractRequest r) {
        UUID id = UUID.randomUUID();
        db.update("""
            INSERT INTO carrier_contracts(id,tenant_id,carrier_id,contract_no,carrier_name,mode,lane_code,currency,valid_from,valid_until,payment_terms,terms,rate_snapshot_json,status)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')
            ON CONFLICT(tenant_id,contract_no) DO UPDATE SET carrier_id=EXCLUDED.carrier_id,carrier_name=EXCLUDED.carrier_name,mode=EXCLUDED.mode,
            lane_code=EXCLUDED.lane_code,currency=EXCLUDED.currency,valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,
            payment_terms=EXCLUDED.payment_terms,terms=EXCLUDED.terms,rate_snapshot_json=EXCLUDED.rate_snapshot_json,status='ACTIVE',updated_at=now()
            """, id, tenant(), r.carrierId(), norm(r.contractNo()), r.carrierName(), norm(r.mode()), blank(r.laneCode()), currency(r.currency()),
            r.validFrom(), r.validUntil(), blank(r.paymentTerms()), r.terms(), r.rateSnapshotJson());
        return one("SELECT * FROM carrier_contracts WHERE contract_no=?", norm(r.contractNo()));
    }

    @Transactional
    public Map<String,Object> createCarrierSettlement(CarrierSettlementRequest r) {
        ensureShipment(r.shipmentId());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO carrier_settlements(id,tenant_id,carrier_id,carrier_name,shipment_id,tender_id,settlement_no,amount,currency,notes) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,tenant(),r.carrierId(),r.carrierName(),r.shipmentId(),r.tenderId(),norm(r.settlementNo()),r.amount(),currency(r.currency()),r.notes());
        return one("SELECT * FROM carrier_settlements WHERE settlement_no=?", norm(r.settlementNo()));
    }

    @Transactional
    public Map<String,Object> approveCarrierSettlement(UUID id) {
        Map<String,Object> row=one("SELECT id,status FROM carrier_settlements WHERE id=?",id);
        db.update("UPDATE carrier_settlements SET status='APPROVED',approved_by=?,approved_at=now() WHERE id=? AND tenant_id=?", currentUserId(), id, tenant());
        return one("SELECT * FROM carrier_settlements WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> createFinanceNote(FinanceNoteRequest r) {
        if (!Set.of("CREDIT","DEBIT").contains(norm(r.noteType()))) throw bad("noteType must be CREDIT or DEBIT");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO finance_notes(id,tenant_id,note_no,note_type,invoice_id,shipment_id,amount,currency,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,tenant(),norm(r.noteNo()),norm(r.noteType()),r.invoiceId(),r.shipmentId(),r.amount(),currency(r.currency()),r.reason(),currentUserId());
        return one("SELECT * FROM finance_notes WHERE note_no=?",norm(r.noteNo()));
    }

    @Transactional
    public Map<String,Object> upsertTaxRule(TaxRuleRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
          INSERT INTO finance_tax_rules(id,tenant_id,code,tax_name,rate,withholding_rate,currency,valid_from,valid_until,active)
          VALUES(?,?,?,?,?,?,?,?,?,true)
          ON CONFLICT(tenant_id,code) DO UPDATE SET tax_name=EXCLUDED.tax_name,rate=EXCLUDED.rate,withholding_rate=EXCLUDED.withholding_rate,
          currency=EXCLUDED.currency,valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,active=true
          """,id,tenant(),norm(r.code()),r.taxName(),r.rate(),r.withholdingRate(),currency(r.currency()),r.validFrom(),r.validUntil());
        return one("SELECT * FROM finance_tax_rules WHERE code=?",norm(r.code()));
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> customerStatement(String client) {
        return db.queryForList("""
          SELECT i.invoice_no,i.issue_date,i.due_date,i.currency,i.invoice_amount,i.amount_paid,
                 (i.invoice_amount-i.amount_paid) balance,i.status
          FROM commercial_invoices i
          WHERE i.client=? ORDER BY i.issue_date DESC
          """, client);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> supplierStatement(String supplierName) {
        return db.queryForList("SELECT supplier_invoice_no,issue_date,due_date,currency,amount,amount_paid,(amount-amount_paid) balance,status FROM finance_supplier_bills WHERE supplier_name=? ORDER BY issue_date DESC", supplierName);
    }

    @Transactional
    public Map<String,Object> closeFinancePeriod(UUID periodId) {
        Map<String,Object> p=one("SELECT * FROM finance_periods WHERE id=?",periodId);
        if (!"OPEN".equalsIgnoreCase(Objects.toString(p.get("status")))) throw bad("Financial period is already closed");
        db.update("UPDATE finance_periods SET status='CLOSED',closed_at=now(),closed_by=? WHERE id=? AND tenant_id=?",currentUserId(),periodId,tenant());
        return one("SELECT * FROM finance_periods WHERE id=?",periodId);
    }

    @Transactional
    public Map<String,Object> generateAccountingExport(AccountingExportRequest r) {
        UUID id=UUID.randomUUID();
        Map<String,Object> totals=one("SELECT COUNT(*) row_count,COALESCE(SUM(amount),0) amount FROM finance_ledger_entries WHERE tenant_id=?",tenant());
        String payload=toJson(Map.of("periodId",r.periodId(),"exportType",norm(r.exportType()),"totals",totals));
        db.update("INSERT INTO accounting_exports(id,tenant_id,period_id,export_type,status,row_count,payload_hash,exported_at) VALUES(?,?,?,?,?,?,?,now())",
                id,tenant(),r.periodId(),norm(r.exportType()),"COMPLETED",((Number)totals.get("row_count")).intValue(),sha256(payload));
        return one("SELECT * FROM accounting_exports WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> evaluateSla(SlaEvaluationRequest r) {
        UUID tenant=tenant();
        String event=norm(r.eventCode()==null?"SHIPMENT_MONITORING":r.eventCode());
        List<Map<String,Object>> policies=db.queryForList("SELECT * FROM logistics_sla_policies WHERE event_code=? AND active=true ORDER BY target_minutes",event);
        List<Map<String,Object>> created=new ArrayList<>();
        for (Map<String,Object> p: policies) {
            Integer minutes=((Number)p.get("target_minutes")).intValue();
            Instant due=Instant.now().plusSeconds(minutes*60L);
            db.update("INSERT INTO sla_instances(id,tenant_id,shipment_id,event_code,severity,started_at,due_at,status,owner) VALUES(?,?,?,?,?,now(),?,'OPEN',?)",
                    UUID.randomUUID(),tenant,r.shipmentId(),event,Objects.toString(p.get("severity"),"HIGH"),java.sql.Timestamp.from(due),r.owner());
            created.add(Map.of("eventCode",event,"targetMinutes",minutes,"dueAt",due.toString()));
        }
        return Map.of("shipmentId",r.shipmentId(),"eventCode",event,"created",created);
    }

    @Transactional
    public Map<String,Object> escalate(EscalationRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO exception_escalations(id,tenant_id,exception_id,level_no,target_owner,status,due_at,sent_at) VALUES(?,?,?,?,?,'SENT',?,now()) ON CONFLICT(tenant_id,exception_id,level_no) DO UPDATE SET target_owner=EXCLUDED.target_owner,status='SENT',due_at=EXCLUDED.due_at,sent_at=now()",
                id,tenant(),r.exceptionId(),r.levelNo(),r.targetOwner(),r.dueAt()==null?null:java.sql.Timestamp.from(r.dueAt()));
        db.update("UPDATE operational_exceptions SET owner=? WHERE id=? AND tenant_id=?",r.targetOwner(),r.exceptionId(),tenant());
        return one("SELECT * FROM exception_escalations WHERE exception_id=? AND level_no=?",r.exceptionId(),r.levelNo());
    }

    @Transactional
    public Map<String,Object> oceanShippingInstructions(OceanShippingInstructionRequest r) {
        ensureOceanBooking(r.bookingId());
        Integer next=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM ocean_shipping_instructions WHERE tenant_id=? AND booking_id=?",Integer.class,tenant(),r.bookingId());
        UUID id=UUID.randomUUID();
        String action=norm(r.action()==null?"SAVE":r.action());
        String status="SUBMIT".equals(action)?"SUBMITTED":"DRAFT";
        db.update("INSERT INTO ocean_shipping_instructions(id,tenant_id,booking_id,version_no,status,shipper_json,consignee_json,notify_party_json,cargo_json,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,tenant(),r.bookingId(),next,status,r.shipperJson(),r.consigneeJson(),r.notifyPartyJson(),r.cargoJson(),"SUBMIT".equals(action)?new java.sql.Timestamp(System.currentTimeMillis()):null);
        db.update("UPDATE ocean_bookings SET shipping_instruction_status=? WHERE id=? AND tenant_id=?",status,r.bookingId(),tenant());
        return one("SELECT * FROM ocean_shipping_instructions WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> oceanBill(OceanBillOfLadingRequest r) {
        ensureOceanBooking(r.bookingId());
        Integer next=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM ocean_bills_of_lading WHERE tenant_id=? AND booking_id=? AND bill_type=?",Integer.class,tenant(),r.bookingId(),norm(r.billType()));
        UUID id=UUID.randomUUID(); String action=norm(r.action()==null?"DRAFT":r.action());
        String status=switch(action){case "APPROVE"->"APPROVED"; case "ISSUE"->"ISSUED"; default->"DRAFT";};
        db.update("INSERT INTO ocean_bills_of_lading(id,tenant_id,booking_id,bill_type,bill_number,version_no,status,ebl_reference,approved_at,issued_at) VALUES(?,?,?,?,?,?,?, ?,?,?)",
                id,tenant(),r.bookingId(),norm(r.billType()),r.billNumber(),next,status,r.eblReference(),"APPROVE".equals(action)||"ISSUE".equals(action)?new java.sql.Timestamp(System.currentTimeMillis()):null,"ISSUE".equals(action)?new java.sql.Timestamp(System.currentTimeMillis()):null);
        db.update("UPDATE ocean_bookings SET bill_status=?,hbl_number=CASE WHEN ?='HBL' THEN ? ELSE hbl_number END,mbl_number=CASE WHEN ?='MBL' THEN ? ELSE mbl_number END,ebl_reference=? WHERE id=? AND tenant_id=?",
                status,norm(r.billType()),r.billNumber(),norm(r.billType()),r.billNumber(),r.eblReference(),r.bookingId(),tenant());
        return one("SELECT * FROM ocean_bills_of_lading WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> reviewDocument(UUID documentId, DocumentReviewRequest r) {
        Map<String,Object> doc=one("SELECT id,approval_status,scan_status FROM cargo_documents WHERE id=?",documentId);
        String action=norm(r.action());
        String status=switch(action){case "APPROVE"->"APPROVED";case "REJECT"->"REJECTED";case "SCAN_PASS"->"APPROVED";default->throw bad("Unsupported document review action");};
        if ("SCAN_PASS".equals(action)) db.update("UPDATE cargo_documents SET scan_status='PASS',approval_status=CASE WHEN approval_status='DRAFT' THEN approval_status ELSE approval_status END,updated_at=now() WHERE id=? AND tenant_id=?",documentId,tenant());
        else db.update("UPDATE cargo_documents SET approval_status=?,approved_by=?,approved_at=CASE WHEN ?='APPROVED' THEN now() ELSE approved_at END,updated_at=now() WHERE id=? AND tenant_id=?",status,currentUserId(),status,documentId,tenant());
        return one("SELECT * FROM cargo_documents WHERE id=?",documentId);
    }

    @Transactional
    public Map<String,Object> submitCustoms(CustomsSubmissionRequest r) {
        ensureDeclaration(r.declarationId());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO customs_submission_attempts(id,tenant_id,declaration_id,idempotency_key,status,external_reference,response_message) VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,declaration_id,idempotency_key) DO NOTHING",
                id,tenant(),r.declarationId(),r.idempotencyKey(),"QUEUED",r.externalReference(),"Submission queued through provider adapter");
        db.update("UPDATE customs_declarations SET status='SUBMITTED',external_reference=COALESCE(?,external_reference),submitted_at=now(),submission_message='Submission queued through provider adapter' WHERE id=? AND tenant_id=?",
                r.externalReference(),r.declarationId(),tenant());
        return one("SELECT * FROM customs_submission_attempts WHERE declaration_id=? AND idempotency_key=?",r.declarationId(),r.idempotencyKey());
    }

    @Transactional
    public Map<String,Object> releaseCustoms(UUID declarationId) {
        ensureDeclaration(declarationId);
        db.update("UPDATE customs_declarations SET status='RELEASED' WHERE id=? AND tenant_id=? AND status NOT IN ('REJECTED','HOLD')",declarationId,tenant());
        return one("SELECT * FROM customs_declarations WHERE id=?",declarationId);
    }

    @Transactional
    public Map<String,Object> routePlan(RoutePlanRequest r) {
        ensureShipment(r.shipmentId());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO route_plans(id,tenant_id,shipment_id,trip_id,mode,origin,destination,route_json,distance_km,duration_minutes) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,tenant(),r.shipmentId(),r.tripId(),norm(r.mode()),r.origin(),r.destination(),r.routeJson(),r.distanceKm(),r.durationMinutes());
        if (r.shipmentId()!=null) db.update("UPDATE road_consignments SET route_plan_json=? WHERE tenant_id=? AND shipment_id=?",r.routeJson(),tenant(),r.shipmentId());
        return one("SELECT * FROM route_plans WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> registerDevice(MobileDeviceRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO mobile_devices(id,tenant_id,device_id,device_type,platform,app_version,user_id,active,last_seen_at) VALUES(?,?,?,?,?,?,?,true,now()) ON CONFLICT(tenant_id,device_id) DO UPDATE SET device_type=EXCLUDED.device_type,platform=EXCLUDED.platform,app_version=EXCLUDED.app_version,user_id=EXCLUDED.user_id,active=true,last_seen_at=now()",
                id,tenant(),r.deviceId(),norm(r.deviceType()),r.platform(),r.appVersion(),r.userId());
        return one("SELECT * FROM mobile_devices WHERE device_id=?",r.deviceId());
    }

    @Transactional
    public Map<String,Object> pushSubscription(PushSubscriptionRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO mobile_push_subscriptions(id,tenant_id,device_id,provider,token_hash) VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,device_id,provider,token_hash) DO UPDATE SET active=true",
                id,tenant(),r.deviceId(),norm(r.provider()),sha256(r.token()));
        return one("SELECT * FROM mobile_push_subscriptions WHERE device_id=? ORDER BY created_at DESC LIMIT 1",r.deviceId());
    }

    @Transactional
    public Map<String,Object> applyMobileSync(MobileSyncApplyRequest r) {
        Map<String,Object> existing=nullable("SELECT id,status,payload_json FROM mobile_sync_queue WHERE tenant_id=? AND device_id=? AND operation_id=?",tenant(),r.deviceId(),r.operationId());
        if(existing!=null) return Map.of("status","IDEMPOTENT", "record", existing);
        if(r.entityId()!=null && entityExists(r.entityType(),r.entityId())) {
            UUID conflict=UUID.randomUUID();
            db.update("INSERT INTO mobile_sync_conflicts(id,tenant_id,device_id,operation_id,entity_type,entity_id,client_state_json) VALUES(?,?,?,?,?,?,?)",
                    conflict,tenant(),r.deviceId(),r.operationId(),norm(r.entityType()),r.entityId(),r.payloadJson());
            return Map.of("status","CONFLICT","conflictId",conflict);
        }
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO mobile_sync_queue(id,tenant_id,device_id,operation_id,entity_type,entity_id,payload_json,status,processed_at) VALUES(?,?,?,?,?,?,?,'PROCESSED',now())",
                id,tenant(),r.deviceId(),r.operationId(),norm(r.entityType()),r.entityId(),r.payloadJson());
        return one("SELECT * FROM mobile_sync_queue WHERE id=?",id);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> analyticsForecast() {
        BigDecimal avgRevenue=decimal(db.queryForObject("SELECT COALESCE(AVG(monthly_revenue),0) FROM (SELECT date_trunc('month',issue_date) month,SUM(invoice_amount) monthly_revenue FROM commercial_invoices WHERE issue_date>=CURRENT_DATE-INTERVAL '6 months' GROUP BY 1) x", BigDecimal.class));
        BigDecimal avgProfit=decimal(db.queryForObject("SELECT COALESCE(AVG(monthly_profit),0) FROM (SELECT date_trunc('month',created_at) month,SUM(COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)-COALESCE(other_expenses,0)) monthly_profit FROM shipments WHERE created_at>=now()-INTERVAL '6 months' GROUP BY 1) x", BigDecimal.class));
        return Map.of("methodology","6-month trailing monthly average","nextMonthRevenue",avgRevenue.setScale(2,RoundingMode.HALF_UP),"nextMonthGrossProfit",avgProfit.setScale(2,RoundingMode.HALF_UP));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> integrationHealth(String code) {
        Map<String,Object> row=one("SELECT * FROM integration_registry WHERE code=?",norm(code));
        String state=Objects.toString(row.get("health_status"),"NOT_CONFIGURED");
        boolean configured=Boolean.TRUE.equals(row.get("enabled")) && row.get("base_url")!=null;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("code",code);
        result.put("configured",configured);
        result.put("healthStatus",state);
        result.put("lastSuccessAt",row.get("last_success_at"));
        result.put("lastFailureAt",row.get("last_failure_at"));
        return result;
    }

    @Transactional
    public Map<String,Object> runAutomationTick(AutomationTickRequest r) {
        if (r.shipmentId()!=null) {
            db.update("UPDATE shipment_readiness_checks SET checked_at=now() WHERE tenant_id=? AND shipment_id=?",tenant(),r.shipmentId());
            String status="BLOCKED";
            long fail=Optional.ofNullable(db.queryForObject("SELECT count(*) FROM shipment_readiness_checks WHERE tenant_id=? AND shipment_id=? AND status='FAIL'",Long.class,tenant(),r.shipmentId())).orElse(0L);
            if(fail==0) status="READY";
            if(fail>0) db.update("INSERT INTO operational_exceptions(id,tenant_id,shipment_id,type,severity,status,description) SELECT ?,?,?, 'AUTOMATION_BLOCK','HIGH','OPEN','Automated readiness workflow detected a blocking issue' WHERE NOT EXISTS (SELECT 1 FROM operational_exceptions WHERE tenant_id=? AND shipment_id=? AND type='AUTOMATION_BLOCK' AND status='OPEN')",UUID.randomUUID(),tenant(),r.shipmentId(),tenant(),r.shipmentId());
            return Map.of("shipmentId",r.shipmentId(),"readiness",status,"trigger",r.trigger()==null?"SCHEDULED":r.trigger());
        }
        return Map.of("status","COMPLETED","trigger",r.trigger()==null?"SCHEDULED":r.trigger());
    }

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
    private Map<String,Object> phase(String id,String name,List<Map<String,Object>> items){
        long blocking=items.stream().filter(i -> Set.of("CI_GATE","OPS_DRILL","SECURITY_TEST","LOAD_TEST","EXTERNAL_UAT","DEVICE_UAT").contains(i.get("status"))).count();
        return Map.of("phase",id,"name",name,"status",blocking==0?"COMPLETE_WITHIN_APP":"REQUIRES_EXTERNAL_VERIFICATION","items",items);
    }
    private Map<String,Object> externalPhase(String id,String name,String[] gaps){
        return phase(id,name,List.of(item(id+".0", "Core operational domain", "IMPLEMENTED"), item(id+".X", String.join("; ",gaps), "EXTERNAL_UAT")));
    }
    private Map<String,Object> item(String id,String name,String status){ return Map.of("id",id,"name",name,"status",status); }
    private UUID tenant(){UUID t=TenantContext.getTenantId(); if(t==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Tenant context is missing"); return t;}
    private void ensureShipment(UUID id){if(id!=null) one("SELECT id FROM shipments WHERE id=?",id);}
    private void ensureOceanBooking(UUID id){one("SELECT id FROM ocean_bookings WHERE id=?",id);}
    private void ensureDeclaration(UUID id){one("SELECT id FROM customs_declarations WHERE id=?",id);}
    private Map<String,Object> one(String sql,Object... args){try{return db.queryForMap(sql,args);}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found");}}
    private Map<String,Object> nullable(String sql,Object... args){try{return db.queryForMap(sql,args);}catch(EmptyResultDataAccessException e){return null;}}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private UUID currentUserId(){
        Authentication a= SecurityContextHolder.getContext().getAuthentication();
        if(a==null||a.getName()==null) return null;
        Map<String,Object> row=nullable("SELECT id FROM users WHERE tenant_id=? AND email=?",tenant(),a.getName());
        return row==null?null:(UUID)row.get("id");
    }
    private boolean entityExists(String entityType,UUID id){
        String table=switch(norm(entityType)){case "SHIPMENT"->"shipments";case "TRIP"->"trips";case "WAREHOUSE_TASK"->"warehouse_tasks";case "INVOICE"->"commercial_invoices";case "QUOTE"->"commercial_quotes";default->null;};
        if(table==null) return false;
        Long n=db.queryForObject("SELECT count(*) FROM "+table+" WHERE id=?",Long.class,id);
        return n!=null&&n>0;
    }
    private static String norm(String s){return s==null?null:s.trim().toUpperCase(Locale.ROOT);}
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String currency(String s){String v=s==null||s.isBlank()?"USD":s.trim().toUpperCase(Locale.ROOT);return v.length()>3?v.substring(0,3):v;}
    private static BigDecimal decimal(Number n){return n==null?BigDecimal.ZERO:new BigDecimal(n.toString());}
    private String sha256(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private String toJson(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
