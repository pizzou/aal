package com.logiplatform.service;

import com.logiplatform.dto.AdvancedEnterpriseDtos.MarginControlRequest;
import com.logiplatform.dto.AdvancedEnterpriseDtos.PricingDiscountRequest;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommercialPricingAdminService {
    private final JdbcTemplate db;

    public CommercialPricingAdminService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) { this.db=db; }

    @Transactional
    public Map<String,Object> discount(PricingDiscountRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO pricing_discounts(id,tenant_id,discount_code,client_id,mode,lane_code,min_charge,discount_percent,valid_from,valid_until,priority,active)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,true)
            ON CONFLICT(tenant_id,discount_code,valid_from) DO UPDATE SET client_id=EXCLUDED.client_id,mode=EXCLUDED.mode,
            lane_code=EXCLUDED.lane_code,min_charge=EXCLUDED.min_charge,discount_percent=EXCLUDED.discount_percent,
            valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,priority=EXCLUDED.priority,active=true
            """, id,TenantContext.getTenantId(),r.discountCode(),r.clientId(),norm(r.mode()),nullable(r.laneCode()),r.minCharge(),r.discountPercent(),r.validFrom(),r.validUntil(),r.priority()==null?100:r.priority());
        return one("SELECT * FROM pricing_discounts WHERE tenant_id=? AND discount_code=?",TenantContext.getTenantId(),r.discountCode());
    }

    @Transactional
    public Map<String,Object> marginControl(MarginControlRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO pricing_margin_controls(id,tenant_id,mode,client_id,minimum_margin_percent,hard_block,valid_from,valid_until,active)
            VALUES(?,?,?,?,?,?,?,?,true)
            ON CONFLICT(tenant_id,mode,client_id,valid_from) DO UPDATE SET minimum_margin_percent=EXCLUDED.minimum_margin_percent,
            hard_block=EXCLUDED.hard_block,valid_until=EXCLUDED.valid_until,active=true
            """,id,TenantContext.getTenantId(),norm(r.mode()),r.clientId(),r.minimumMarginPercent(),r.hardBlock(),r.validFrom(),r.validUntil());
        return one("SELECT * FROM pricing_margin_controls WHERE id=? AND tenant_id=?",id,TenantContext.getTenantId());
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> discounts() { return db.queryForList("SELECT * FROM pricing_discounts WHERE tenant_id=? ORDER BY priority,valid_from DESC",TenantContext.getTenantId()); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> marginControls() { return db.queryForList("SELECT * FROM pricing_margin_controls WHERE tenant_id=? ORDER BY valid_from DESC",TenantContext.getTenantId()); }

    private Map<String,Object> one(String sql,Object...args){return db.queryForMap(sql,args);}
    private static String norm(String value){return value==null?null:value.trim().toUpperCase();}
    private static String nullable(String value){return value==null||value.isBlank()?null:value.trim();}
}
