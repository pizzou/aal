package com.logiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.model.CommercialQuote;
import com.logiplatform.repository.CommercialQuoteRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class QuoteLifecycleService {
    private final JdbcTemplate db;
    private final CommercialQuoteRepository quotes;
    private final ObjectMapper mapper;

    public QuoteLifecycleService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                 CommercialQuoteRepository quotes,
                                 ObjectMapper mapper) {
        this.db = db; this.quotes = quotes; this.mapper = mapper;
    }

    @Transactional
    public Map<String,Object> ensureInitialVersion(UUID quoteId) {
        UUID tenant = TenantContext.getTenantId();
        CommercialQuote q = quote(quoteId);
        Map<String,Object> existing = latest(quoteId);
        return existing != null ? existing : createVersion(q);
    }

    @Transactional
    public Map<String,Object> createVersion(UUID quoteId) {
        return createVersion(quote(quoteId));
    }

    @Transactional
    public Map<String,Object> revise(UUID quoteId, Map<String,Object> changes) {
        CommercialQuote q = quote(quoteId); UUID tenant = TenantContext.getTenantId();
        BigDecimal supplier = decimal(changes.get("supplierCost"), q.getSupplierCost());
        BigDecimal other = decimal(changes.get("otherCost"), q.getOtherCost());
        BigDecimal amount = decimal(changes.get("quotedAmount"), q.getQuotedAmount());
        BigDecimal profit = amount.subtract(supplier.add(other));
        String currency = string(changes.get("currency"), q.getCurrency());
        db.update("UPDATE commercial_quotes SET supplier_cost=?,other_cost=?,quoted_amount=?,expected_profit=?,currency=?,incoterm=COALESCE(?,incoterm),tax_rate=COALESCE(?,tax_rate),tax_amount=COALESCE(?,tax_amount),customs_cost=COALESCE(?,customs_cost),insurance_cost=COALESCE(?,insurance_cost),customer_credit_terms=COALESCE(?,customer_credit_terms),price_locked_at=NULL,locked_amount=NULL,locked_currency=NULL,approved_at=NULL,approved_by=NULL,status='DRAFT',updated_at=COALESCE(updated_at,now()) WHERE id=? AND tenant_id=?",
                supplier,other,amount,profit,currency,changes.get("incoterm"),changes.get("taxRate"),changes.get("taxAmount"),changes.get("customsCost"),changes.get("insuranceCost"),changes.get("customerCreditTerms"),quoteId,tenant);
        return createVersion(quote(quoteId));
    }

    private static BigDecimal decimal(Object v, BigDecimal fallback) {
        if (v == null) return fallback == null ? BigDecimal.ZERO : fallback;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid numeric quote value"); }
    }
    private static String string(Object v, String fallback) { return v == null || v.toString().isBlank() ? (fallback == null ? "USD" : fallback) : v.toString().trim().toUpperCase(); }

    private Map<String,Object> createVersion(CommercialQuote q) {
        UUID tenant = TenantContext.getTenantId();
        Integer next = db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM commercial_quote_versions WHERE tenant_id=? AND quote_id=?", Integer.class, tenant, q.getId());
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("quoteId", q.getQuoteId()); snapshot.put("quoteDate", q.getQuoteDate()); snapshot.put("client", q.getClient());
        snapshot.put("route", q.getRoute()); snapshot.put("serviceType", q.getServiceType()); snapshot.put("commodity", q.getCommodity());
        snapshot.put("chargeableWeightKg", q.getChargeableWeightKg()); snapshot.put("supplierCost", q.getSupplierCost());
        snapshot.put("otherCost", q.getOtherCost()); snapshot.put("markupPercent", q.getMarkupPercent());
        snapshot.put("quotedAmount", q.getQuotedAmount()); snapshot.put("expectedProfit", q.getExpectedProfit());
        snapshot.put("currency", q.getCurrency()); snapshot.put("incoterm", q.getIncoterm()); snapshot.put("taxRate", q.getTaxRate());
        snapshot.put("taxAmount", q.getTaxAmount()); snapshot.put("customsCost", q.getCustomsCost()); snapshot.put("insuranceCost", q.getInsuranceCost());
        snapshot.put("customerCreditTerms", q.getCustomerCreditTerms()); snapshot.put("validUntil", q.getValidUntil());
        String json;
        try { json = mapper.writeValueAsString(snapshot); } catch (Exception e) { throw new IllegalStateException("Unable to snapshot quotation", e); }
        UUID id = UUID.randomUUID();
        UUID user = currentUser();
        db.update("INSERT INTO commercial_quote_versions(id,tenant_id,quote_id,version_no,snapshot_json,currency,amount,status,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                id, tenant, q.getId(), next, json, q.getCurrency(), q.getQuotedAmount()==null?BigDecimal.ZERO:q.getQuotedAmount(), "DRAFT", user);
        return version(tenant, id);
    }

    @Transactional
    public Map<String,Object> lock(UUID quoteId, UUID versionId) {
        CommercialQuote q = quote(quoteId);
        Map<String,Object> v = versionFor(q.getId(), versionId);
        if (v == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote version not found");
        if (Boolean.TRUE.equals(v.get("locked"))) return v;
        UUID tenant = TenantContext.getTenantId();
        db.update("UPDATE commercial_quote_versions SET locked=true,locked_at=COALESCE(locked_at,now()),status='LOCKED' WHERE id=? AND tenant_id=? AND quote_id=?", v.get("id"), tenant, q.getId());
        q.lockPrice(); quotes.save(q);
        return version(tenant, (UUID)v.get("id"));
    }

    @Transactional
    public Map<String,Object> approve(UUID quoteId, UUID versionId) {
        CommercialQuote q = quote(quoteId);
        Map<String,Object> v = versionFor(q.getId(), versionId);
        if (v == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote version not found");
        if (!Boolean.TRUE.equals(v.get("locked"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Lock the quote price before approval");
        UUID user = currentUser(); UUID tenant = TenantContext.getTenantId();
        db.update("UPDATE commercial_quote_versions SET status='APPROVED',approved_by=?,approved_at=now() WHERE id=? AND tenant_id=?", user, v.get("id"), tenant);
        q.approve(user); quotes.save(q);
        return version(tenant, (UUID)v.get("id"));
    }

    @Transactional
    public Map<String,Object> accept(UUID quoteId, UUID versionId) {
        CommercialQuote q = quote(quoteId);
        Map<String,Object> v = versionFor(q.getId(), versionId);
        if (v == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote version not found");
        if (!Boolean.TRUE.equals(v.get("locked"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a locked quote can be accepted");
        UUID tenant = TenantContext.getTenantId();
        db.update("UPDATE commercial_quote_versions SET status='ACCEPTED' WHERE id=? AND tenant_id=?", v.get("id"), tenant);
        q.acceptVersion((UUID)v.get("id")); quotes.save(q);
        return version(tenant, (UUID)v.get("id"));
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> versions(UUID quoteId) {
        quote(quoteId);
        UUID tenant = TenantContext.getTenantId();
        return db.query("SELECT id,version_no,snapshot_json,currency,amount,status,locked,locked_at,created_by,approved_by,approved_at,created_at FROM commercial_quote_versions WHERE tenant_id=? AND quote_id=? ORDER BY version_no DESC", (rs,n)-> {
            Map<String,Object> m=new LinkedHashMap<>(); m.put("id",rs.getObject("id",UUID.class)); m.put("versionNo",rs.getInt("version_no"));
            m.put("snapshotJson",rs.getString("snapshot_json")); m.put("currency",rs.getString("currency")); m.put("amount",rs.getBigDecimal("amount"));
            m.put("status",rs.getString("status")); m.put("locked",rs.getBoolean("locked")); m.put("lockedAt",rs.getTimestamp("locked_at"));
            m.put("createdBy",rs.getObject("created_by",UUID.class)); m.put("approvedBy",rs.getObject("approved_by",UUID.class)); m.put("approvedAt",rs.getTimestamp("approved_at")); m.put("createdAt",rs.getTimestamp("created_at")); return m;
        }, tenant, quoteId);
    }

    public Map<String,Object> latest(UUID quoteId) {
        UUID tenant = TenantContext.getTenantId();
        List<Map<String,Object>> rows = db.query("SELECT id,version_no,snapshot_json,currency,amount,status,locked,locked_at,created_by,approved_by,approved_at,created_at FROM commercial_quote_versions WHERE tenant_id=? AND quote_id=? ORDER BY version_no DESC LIMIT 1", (rs,n)->row(rs), tenant, quoteId);
        return rows.isEmpty()?null:rows.get(0);
    }

    private Map<String,Object> versionFor(UUID quoteId, UUID versionId) {
        UUID tenant=TenantContext.getTenantId();
        if(versionId==null) return latest(quoteId);
        List<Map<String,Object>> rows=db.query("SELECT id,version_no,snapshot_json,currency,amount,status,locked,locked_at,created_by,approved_by,approved_at,created_at FROM commercial_quote_versions WHERE tenant_id=? AND quote_id=? AND id=?",(rs,n)->row(rs),tenant,quoteId,versionId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> version(UUID tenant, UUID id){return db.queryForObject("SELECT id,version_no,snapshot_json,currency,amount,status,locked,locked_at,created_by,approved_by,approved_at,created_at FROM commercial_quote_versions WHERE tenant_id=? AND id=?",(rs,n)->row(rs),tenant,id);}
    private Map<String,Object> row(java.sql.ResultSet rs){try{Map<String,Object>m=new LinkedHashMap<>();m.put("id",rs.getObject("id",UUID.class));m.put("versionNo",rs.getInt("version_no"));m.put("snapshotJson",rs.getString("snapshot_json"));m.put("currency",rs.getString("currency"));m.put("amount",rs.getBigDecimal("amount"));m.put("status",rs.getString("status"));m.put("locked",rs.getBoolean("locked"));m.put("lockedAt",rs.getTimestamp("locked_at"));m.put("createdBy",rs.getObject("created_by",UUID.class));m.put("approvedBy",rs.getObject("approved_by",UUID.class));m.put("approvedAt",rs.getTimestamp("approved_at"));m.put("createdAt",rs.getTimestamp("created_at"));return m;}catch(Exception e){throw new IllegalStateException(e);}}
    private CommercialQuote quote(UUID id){return quotes.findById(id).filter(q->TenantContext.getTenantId().equals(q.getTenantId())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Quote not found"));}
    private UUID currentUser(){try{Object p=SecurityContextHolder.getContext().getAuthentication().getPrincipal(); if(p instanceof com.logiplatform.security.TenantPrincipal tp)return tp.userId();}catch(Exception ignored){} return null;}
}
