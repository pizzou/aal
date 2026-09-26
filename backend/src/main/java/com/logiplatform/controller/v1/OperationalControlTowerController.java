package com.logiplatform.controller.v1;

import com.logiplatform.integration.control.ProviderHealthService;
import com.logiplatform.service.control.ReconciliationTaskService;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/control-tower")
public class OperationalControlTowerController {
    private final JdbcTemplate db; private final ProviderHealthService health; private final ReconciliationTaskService reconciliation;
    public OperationalControlTowerController(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,ProviderHealthService health,ReconciliationTaskService reconciliation){this.db=db;this.health=health;this.reconciliation=reconciliation;}
    @GetMapping("/operational")
    public Map<String,Object> operational(){
        UUID t=TenantContext.getTenantId();
        Map<String,Object> out=new LinkedHashMap<>(); out.put("generatedAt",java.time.Instant.now()); out.put("integrations",health.current()); out.put("reconciliation",reconciliation.metrics());
        out.put("dlq",db.queryForMap("SELECT COUNT(*) FILTER(WHERE status IN ('OPEN','RETRYING')) AS size,MIN(created_at) AS oldest FROM airline_integration_dead_letters WHERE tenant_id=?",t));
        out.put("shipmentsAtRisk",db.queryForList("SELECT id,reference_code,status,eta,etd FROM shipments WHERE tenant_id=? AND status NOT IN ('DELIVERED','CANCELLED') AND ((eta IS NOT NULL AND eta<now()) OR (etd IS NOT NULL AND etd<now() AND status NOT IN ('DEPARTED','ARRIVED'))) ORDER BY COALESCE(eta,etd) ASC LIMIT 100",t));
        out.put("bookingExceptions",db.queryForList("SELECT id,shipment_id,status,provider,provider_reference,updated_at FROM air_cargo_bookings WHERE tenant_id=? AND status IN ('UNKNOWN','FAILED','AMENDMENT_PENDING','CANCELLATION_PENDING') ORDER BY updated_at DESC LIMIT 100",t));
        out.put("awbFailures",db.queryForList("SELECT id,awb_number,validation_status,submission_status,carrier_reference,updated_at FROM awb_records WHERE tenant_id=? AND (validation_status IN ('FAILED','REJECTED') OR submission_status IN ('FAILED','REJECTED','PENDING')) ORDER BY updated_at DESC LIMIT 100",t));
        out.put("customsExceptions",db.queryForList("SELECT id,shipment_id,declaration_type,status,external_reference,submission_message,submitted_at FROM customs_declarations WHERE tenant_id=? AND status IN ('REJECTED','FAILED','PENDING') ORDER BY submitted_at DESC NULLS LAST LIMIT 100",t));
        out.put("documentsMissing",db.queryForList("SELECT s.id,s.reference_code FROM shipments s WHERE s.tenant_id=? AND s.status NOT IN ('DELIVERED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM cargo_documents d WHERE d.tenant_id=s.tenant_id AND d.shipment_id=s.id) ORDER BY s.created_at DESC LIMIT 100",t));
        out.put("dgExceptions",db.queryForList("SELECT id,shipment_id,status,validation_message FROM dangerous_goods_declarations WHERE tenant_id=? AND status IN ('REJECTED','EXCEPTION','PENDING') ORDER BY created_at DESC LIMIT 100",t));
        return out;
    }
}
