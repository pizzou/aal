package com.logiplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.dto.AdvancedLogisticsDtos.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class AdvancedLogisticsService {
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public AdvancedLogisticsService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    public Map<String,Object> capabilities() {
        return Map.of(
            "canonicalStores", List.of("shipments","commercial_quotes","commercial_invoices","commercial_payments","client_records"),
            "phases", List.of("EXCEL_MIGRATION","SHIPMENT_360","ADVANCED_RATING","CUSTOMER_JOURNEY","AIR","OCEAN","ROAD","WAREHOUSE","CUSTOMS","DOCUMENTS","FINANCE","CONTROL_TOWER","CARRIER_MANAGEMENT","ANALYTICS","AUTOMATION","INTEGRATIONS","SECURITY","MOBILE"),
            "standardsReady", List.of("IATA_ONE_RECORD","DCSA_TRACK_TRACE","DCSA_BOOKING","WCO_DATA_MODEL","FIATA_EFBL"),
            "externalIntegrations", "ADAPTER_READY_REQUIRES_PROVIDER_CREDENTIALS_AND_UAT"
        );
    }

    @Transactional(readOnly = true)
    public Map<String,Object> shipment360(UUID shipmentId) {
        tenant(shipmentId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("shipment", one("SELECT * FROM shipments WHERE id=?", shipmentId));
        result.put("parties", rows("SELECT * FROM shipment_parties WHERE shipment_id=? ORDER BY created_at", shipmentId));
        result.put("cargo", rows("SELECT * FROM cargo_items WHERE shipment_id=? ORDER BY line_no", shipmentId));
        result.put("pieces", rows("SELECT * FROM cargo_pieces WHERE shipment_id=? ORDER BY piece_no", shipmentId));
        result.put("legs", rows("SELECT * FROM transport_legs WHERE shipment_id=? ORDER BY sequence_no", shipmentId));
        result.put("journeyLegs", rows("SELECT * FROM transport_plan_legs WHERE shipment_id=? ORDER BY sequence_no", shipmentId));
        result.put("milestones", rows("SELECT * FROM shipment_milestones WHERE shipment_id=? ORDER BY planned_at NULLS LAST, actual_at", shipmentId));
        result.put("legMilestones", rows("SELECT m.* FROM transport_leg_milestones m JOIN transport_legs l ON l.id=m.leg_id WHERE l.shipment_id=? ORDER BY m.planned_at NULLS LAST, m.actual_at", shipmentId));
        result.put("documents", rows("SELECT * FROM cargo_documents WHERE shipment_id=? ORDER BY document_type,version_no DESC", shipmentId));
        result.put("legDocuments", rows("SELECT d.* FROM transport_leg_documents d JOIN transport_legs l ON l.id=d.leg_id WHERE l.shipment_id=? ORDER BY d.created_at DESC", shipmentId));
        result.put("costs", rows("SELECT c.* FROM transport_leg_costs c JOIN transport_legs l ON l.id=c.leg_id WHERE l.shipment_id=? ORDER BY c.created_at DESC", shipmentId));
        result.put("tracking", rows("SELECT * FROM shipment_tracking_events WHERE shipment_id=? ORDER BY occurred_at", shipmentId));
        result.put("invoices", rows("SELECT * FROM commercial_invoices WHERE shipment_id=? ORDER BY issue_date DESC", shipmentId));
        result.put("payments", rows("SELECT p.* FROM commercial_payments p JOIN commercial_invoices i ON i.id=p.invoice_id WHERE i.shipment_id=? ORDER BY p.created_at DESC", shipmentId));
        result.put("exceptions", rows("SELECT * FROM operational_exceptions WHERE shipment_id=? ORDER BY created_at DESC", shipmentId));
        result.put("customs", rows("SELECT * FROM customs_declarations WHERE shipment_id=? ORDER BY submitted_at DESC NULLS LAST", shipmentId));
        result.put("customsWorkflows", rows("SELECT * FROM customs_workflows WHERE shipment_id=? ORDER BY created_at DESC", shipmentId));
        result.put("pod", rows("SELECT * FROM proof_of_delivery WHERE shipment_id=?", shipmentId));
        result.put("oceanCharges", rows("SELECT * FROM ocean_charge_events WHERE shipment_id=? ORDER BY event_date DESC", shipmentId));
        result.put("supplierBills", rows("SELECT * FROM finance_supplier_bills WHERE shipment_id=? ORDER BY issue_date DESC", shipmentId));
        result.put("readiness", rows("SELECT * FROM shipment_readiness_checks WHERE shipment_id=? ORDER BY severity DESC,check_code", shipmentId));
        result.put("profitability", profitability(shipmentId));
        return result;
    }

    @Transactional
    public Map<String,Object> advancedRate(RatePreviewRequest r) {
        String mode = norm(r.mode());
        BigDecimal weight = nz(r.weightKg());
        String currency = normCurrency(r.currency());
        Map<String,Object> customerCard = findCustomerRateCard(r.clientId(), r.laneCode(), mode);
        Map<String,Object> rule = customerCard == null
                ? findPricingRule(r.clientId(), r.carrierId(), r.laneCode(), mode)
                : null;
        BigDecimal baseRate;
        BigDecimal minCharge;
        BigDecimal fuel;
        BigDecimal security = BigDecimal.ZERO;
        BigDecimal tax;
        if (customerCard != null) {
            baseRate = dec(customerCard.get("base_rate_per_kg"));
            minCharge = dec(customerCard.get("min_charge"));
            fuel = dec(customerCard.get("fuel_percent"));
            tax = BigDecimal.ZERO;
            currency = Objects.toString(customerCard.get("currency"), currency);
        } else if (rule != null) {
            baseRate = dec(rule.get("rate_per_kg"));
            minCharge = dec(rule.get("min_charge"));
            fuel = dec(rule.get("fuel_percent"));
            tax = dec(rule.get("tax_percent"));
            currency = Objects.toString(rule.get("currency"), currency);
        } else {
            Map<String,Object> card = findRateCard(r.clientId(), r.carrierId(), r.laneCode(), mode);
            if (card == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No active rate configured for " + mode);
            baseRate = dec(card.get("base_rate_per_kg"));
            minCharge = dec(card.get("min_charge"));
            fuel = dec(card.get("fuel_surcharge_percent"));
            tax = BigDecimal.ZERO;
            currency = Objects.toString(card.get("currency"), currency);
        }

        BigDecimal raw = baseRate.multiply(weight).setScale(4, RoundingMode.HALF_UP);
        BigDecimal base = raw.max(minCharge);
        BigDecimal fuelAmount = pct(base, fuel);
        BigDecimal securityAmount = pct(base, security);
        BigDecimal accessorial = BigDecimal.ZERO;
        BigDecimal buy = BigDecimal.ZERO;
        List<Map<String,Object>> charges = new ArrayList<>();

        if (r.charges() != null) {
            for (ChargeInput c : r.charges()) {
                BigDecimal sell = nz(c.amount());
                BigDecimal buyAmount = c.buyAmount() == null ? sell : c.buyAmount();
                accessorial = accessorial.add(sell);
                buy = buy.add(buyAmount);
                Map<String,Object> charge = new LinkedHashMap<>();
                charge.put("code", c.code());
                charge.put("category", c.category() == null ? "ACCESSORIAL" : c.category());
                charge.put("description", c.description() == null ? c.code() : c.description());
                charge.put("sellAmount", sell);
                charge.put("buyAmount", buyAmount);
                charge.put("currency", normCurrency(c.currency()));
                charges.add(charge);
            }
        }

        BigDecimal subtotal = base.add(fuelAmount).add(securityAmount).add(accessorial);
        BigDecimal taxAmount = pct(subtotal, tax);
        BigDecimal total = subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal buyTotal = buy.setScale(2, RoundingMode.HALF_UP);
        BigDecimal profit = total.subtract(buyTotal);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("mode", mode); result.put("currency", currency); result.put("weightKg", weight);
        result.put("rawWeightCharge", raw); result.put("baseCharge", base);
        result.put("fuelSurcharge", fuelAmount); result.put("securitySurcharge", securityAmount);
        result.put("accessorialTotal", accessorial); result.put("taxAmount", taxAmount);
        result.put("totalCharge", total); result.put("buyTotal", buyTotal);
        result.put("expectedProfit", profit);
        result.put("marginPercent", total.signum() == 0 ? BigDecimal.ZERO : profit.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP));
        result.put("pricingSource", customerCard != null ? "CUSTOMER_RATE_CARD" : (rule != null ? "PRICING_RULE" : "RATE_CARD"));
        result.put("charges", charges);
        return result;
    }

    @Transactional
    public Map<String,Object> pricingRule(PricingRuleRequest r) {
        UUID id = UUID.randomUUID();
        db.update("""
          INSERT INTO pricing_rules(id,tenant_id,rule_code,priority,mode,lane_code,client_id,carrier_id,
          min_weight_kg,max_weight_kg,min_charge,rate_per_kg,markup_percent,fuel_percent,tax_percent,
          currency,valid_from,valid_until,active,conditions_json)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?)
          ON CONFLICT(tenant_id,rule_code) DO UPDATE SET priority=EXCLUDED.priority,mode=EXCLUDED.mode,
          lane_code=EXCLUDED.lane_code,client_id=EXCLUDED.client_id,carrier_id=EXCLUDED.carrier_id,
          min_weight_kg=EXCLUDED.min_weight_kg,max_weight_kg=EXCLUDED.max_weight_kg,min_charge=EXCLUDED.min_charge,
          rate_per_kg=EXCLUDED.rate_per_kg,markup_percent=EXCLUDED.markup_percent,fuel_percent=EXCLUDED.fuel_percent,
          tax_percent=EXCLUDED.tax_percent,currency=EXCLUDED.currency,valid_from=EXCLUDED.valid_from,
          valid_until=EXCLUDED.valid_until,conditions_json=EXCLUDED.conditions_json,active=true
          """,
          id,TenantContext.getTenantId(),r.ruleCode(),r.priority()==null?100:r.priority(),norm(r.mode()),r.laneCode(),
          r.clientId(),r.carrierId(),r.minWeightKg(),r.maxWeightKg(),r.minCharge(),r.ratePerKg(),r.markupPercent(),
          r.fuelPercent(),r.taxPercent(),normCurrency(r.currency()),r.validFrom(),r.validUntil(),r.conditionsJson());
        return one("SELECT * FROM pricing_rules WHERE rule_code=?",r.ruleCode());
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> pricingRules() {
        return rows("SELECT * FROM pricing_rules WHERE active=true ORDER BY priority,valid_from DESC");
    }

    @Transactional
    public Map<String,Object> customerRateCard(CustomerRateCardRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
          INSERT INTO customer_rate_cards(id,tenant_id,client_id,card_code,mode,lane_code,base_rate_per_kg,min_charge,
          fuel_percent,security_percent,currency,valid_from,valid_until,active,terms)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,true,?)
          ON CONFLICT(tenant_id,card_code) DO UPDATE SET client_id=EXCLUDED.client_id,mode=EXCLUDED.mode,
          lane_code=EXCLUDED.lane_code,base_rate_per_kg=EXCLUDED.base_rate_per_kg,min_charge=EXCLUDED.min_charge,
          fuel_percent=EXCLUDED.fuel_percent,security_percent=EXCLUDED.security_percent,currency=EXCLUDED.currency,
          valid_from=EXCLUDED.valid_from,valid_until=EXCLUDED.valid_until,terms=EXCLUDED.terms,active=true
          """,id,TenantContext.getTenantId(),r.clientId(),r.cardCode(),norm(r.mode()),r.laneCode(),r.baseRatePerKg(),r.minCharge(),
          r.fuelPercent(),r.securityPercent(),normCurrency(r.currency()),r.validFrom(),r.validUntil(),r.terms());
        return one("SELECT * FROM customer_rate_cards WHERE card_code=?",r.cardCode());
    }

    @Transactional
    public Map<String,Object> carrierBuyRate(CarrierBuyRateRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
          INSERT INTO carrier_buy_rates(id,tenant_id,carrier_id,carrier_name,mode,lane_code,base_rate_per_kg,min_charge,
          fuel_percent,security_percent,currency,valid_from,valid_until,active,contract_reference)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,true,?)
          ON CONFLICT(tenant_id,carrier_name,mode,lane_code,valid_from) DO UPDATE SET carrier_id=EXCLUDED.carrier_id,
          base_rate_per_kg=EXCLUDED.base_rate_per_kg,min_charge=EXCLUDED.min_charge,fuel_percent=EXCLUDED.fuel_percent,
          security_percent=EXCLUDED.security_percent,currency=EXCLUDED.currency,valid_until=EXCLUDED.valid_until,
          contract_reference=EXCLUDED.contract_reference,active=true
          """,id,TenantContext.getTenantId(),r.carrierId(),r.carrierName(),norm(r.mode()),r.laneCode(),r.baseRatePerKg(),r.minCharge(),
          r.fuelPercent(),r.securityPercent(),normCurrency(r.currency()),r.validFrom(),r.validUntil(),r.contractReference());
        return one("SELECT * FROM carrier_buy_rates WHERE carrier_name=? AND mode=? AND valid_from=?",r.carrierName(),norm(r.mode()),r.validFrom());
    }

    @Transactional
    public Map<String,Object> addQuoteCharge(UUID quoteId, QuoteChargeRequest r) {
        Map<String,Object> quote = one("SELECT id,status,locked_amount FROM commercial_quotes WHERE id=?", quoteId);
        if (quote.get("locked_amount") != null ||
            Set.of("ACCEPTED","LOCKED","CANCELLED").contains(Objects.toString(quote.get("status"),"").toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote pricing is locked");
        }
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO commercial_quote_charges(id,tenant_id,quote_id,code,category,description,buy_amount,sell_amount,currency,quantity,unit_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id, TenantContext.getTenantId(), quoteId, r.code(), r.category(), r.description(), nz(r.buyAmount()), nz(r.sellAmount()), normCurrency(r.currency()),
            r.quantity() == null || r.quantity().signum()==0 ? BigDecimal.ONE : r.quantity(), r.unitRate());
        Map<String,Object> totals = one("SELECT COALESCE(SUM(buy_amount),0) buy_total,COALESCE(SUM(sell_amount),0) sell_total FROM commercial_quote_charges WHERE quote_id=?", quoteId);
        BigDecimal buy = dec(totals.get("buy_total"));
        BigDecimal sell = dec(totals.get("sell_total"));
        BigDecimal profit = sell.subtract(buy);
        BigDecimal markup = buy.signum()==0 ? BigDecimal.ZERO : profit.multiply(BigDecimal.valueOf(100)).divide(buy,4,RoundingMode.HALF_UP);
        db.update("UPDATE commercial_quotes SET supplier_cost=?,quoted_amount=?,expected_profit=?,markup_percent=?,updated_at=now() WHERE id=?",
            buy,sell,profit,markup,quoteId);
        return one("SELECT * FROM commercial_quote_charges WHERE id=?", id);
    }

    @Transactional
    public Map<String,Object> readiness(UUID shipmentId, ReadinessRequest request) {
        tenant(shipmentId);
        db.update("DELETE FROM shipment_readiness_checks WHERE shipment_id=?", shipmentId);
        List<Check> checks = new ArrayList<>();
        Map<String,Object> shipment = one("SELECT * FROM shipments WHERE id=?", shipmentId);
        checks.add(check("SHIPMENT_REFERENCE", shipment.get("reference_code") != null, "Shipment reference exists"));
        checks.add(check("CARGO", count("SELECT count(*) FROM cargo_items WHERE shipment_id=?", shipmentId)>0, "At least one cargo line is recorded"));
        checks.add(check("JOURNEY", count("SELECT count(*) FROM transport_legs WHERE shipment_id=?", shipmentId)>0 || count("SELECT count(*) FROM transport_plan_legs WHERE shipment_id=?", shipmentId)>0, "A transport leg is planned"));
        checks.add(check("ETA", shipment.get("eta") != null || count("SELECT count(*) FROM transport_legs WHERE shipment_id=? AND planned_arrival IS NOT NULL", shipmentId)>0, "An ETA/planned arrival exists"));
        checks.add(check("DOCUMENTS", count("SELECT count(*) FROM cargo_documents WHERE shipment_id=?", shipmentId)>0, "At least one shipment document exists"));
        checks.add(check("INVOICE", count("SELECT count(*) FROM commercial_invoices WHERE shipment_id=?", shipmentId)>0, "Canonical invoice exists"));
        checks.add(check("CUSTOMS", !"CUSTOMS".equalsIgnoreCase(Objects.toString(shipment.get("status"), "")) || count("SELECT count(*) FROM customs_declarations WHERE shipment_id=?", shipmentId)>0, "Customs declaration exists when shipment is in customs"));
        for (Check c : checks) {
            db.update("INSERT INTO shipment_readiness_checks(id,tenant_id,shipment_id,check_code,status,severity,message) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(), TenantContext.getTenantId(), shipmentId, c.code, c.ok ? "PASS" : "FAIL", c.ok ? "INFO" : "HIGH", c.message);
        }
        long failed = checks.stream().filter(c -> !c.ok).count();
        return Map.of("shipmentId", shipmentId, "status", failed == 0 ? "READY" : "BLOCKED", "passed", checks.size()-failed, "failed", failed, "checks", checks);
    }

    @Transactional
    public Map<String,Object> evaluateExceptions(UUID shipmentId) {
        tenant(shipmentId);
        Map<String,Object> shipment = one("SELECT * FROM shipments WHERE id=?", shipmentId);
        List<Map<String,Object>> created = new ArrayList<>();
        if (shipment.get("eta") instanceof java.sql.Timestamp eta && eta.toInstant().isBefore(Instant.now()) &&
            !Set.of("DELIVERED","COMPLETED","CANCELLED").contains(Objects.toString(shipment.get("status"),""))) {
            created.add(exception(shipmentId, "ETA_BREACH", "HIGH", "Shipment ETA has passed without delivery"));
        }
        if (count("SELECT count(*) FROM customs_declarations WHERE shipment_id=? AND status IN ('HOLD','REJECTED')", shipmentId)>0)
            created.add(exception(shipmentId, "CUSTOMS_HOLD", "CRITICAL", "Customs declaration is on hold or rejected"));
        if (count("SELECT count(*) FROM shipment_readiness_checks WHERE shipment_id=? AND status='FAIL'", shipmentId)>0)
            created.add(exception(shipmentId, "READINESS_BLOCK", "HIGH", "Pre-departure readiness checks have blocking failures"));
        return Map.of("shipmentId", shipmentId, "created", created);
    }

    @Transactional
    public Map<String,Object> oceanFreeTimeRule(OceanFreeTimeRuleRequest r) {
        UUID id=UUID.randomUUID();
        db.update("""
          INSERT INTO ocean_free_time_rules(id,tenant_id,carrier_name,port_code,container_type,free_days,demurrage_per_day,detention_per_day,currency,valid_from,valid_until,active)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,true)
          """,id,TenantContext.getTenantId(),r.carrierName(),r.portCode(),r.containerType(),r.freeDays(),r.demurragePerDay(),
          r.detentionPerDay(),normCurrency(r.currency()),r.validFrom(),r.validUntil());
        return one("SELECT * FROM ocean_free_time_rules WHERE id=?",id);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> oceanFreeTimeRules() {
        return rows("SELECT * FROM ocean_free_time_rules WHERE active=true ORDER BY valid_from DESC");
    }

    @Transactional
    public Map<String,Object> oceanCharge(UUID shipmentId, OceanChargeRequest r) {
        tenant(shipmentId);
        BigDecimal amount = nz(r.dailyRate()).multiply(BigDecimal.valueOf(nzInt(r.billableDays())));
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO ocean_charge_events(id,tenant_id,shipment_id,container_id,charge_type,event_date,free_days,billable_days,daily_rate,amount,currency,status,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,TenantContext.getTenantId(),shipmentId,r.containerId(),norm(r.chargeType()),r.eventDate(),r.freeDays(),r.billableDays(),r.dailyRate(),amount,normCurrency(r.currency()),"CALCULATED",r.notes());
        return one("SELECT * FROM ocean_charge_events WHERE id=?", id);
    }

    @Transactional
    public Map<String,Object> barcode(WarehouseBarcodeRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO warehouse_barcodes(id,tenant_id,shipment_id,inventory_item_id,barcode,barcode_type) VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,barcode) DO UPDATE SET shipment_id=EXCLUDED.shipment_id,inventory_item_id=EXCLUDED.inventory_item_id,status='ACTIVE'",
            id,TenantContext.getTenantId(),r.shipmentId(),r.inventoryItemId(),r.barcode(),norm(r.barcodeType()==null?"CODE128":r.barcodeType()));
        return one("SELECT * FROM warehouse_barcodes WHERE barcode=?",r.barcode());
    }

    @Transactional
    public Map<String,Object> cycleCount(CycleCountRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO warehouse_cycle_counts(id,tenant_id,warehouse_id,location_code,scheduled_at,expected_quantity,counted_by) VALUES(?,?,?,?,?,?,?)",
            id,TenantContext.getTenantId(),r.warehouseId(),r.locationCode(),r.scheduledAt(),r.expectedQuantity(),r.countedBy());
        return one("SELECT * FROM warehouse_cycle_counts WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> customsLine(CustomsLineRequest r) {
        ensureDeclaration(r.declarationId());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO customs_declaration_lines(id,tenant_id,declaration_id,line_no,hs_code,description,country_of_origin,quantity,unit_value,declared_value,currency,duty_rate,tax_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,declaration_id,line_no) DO UPDATE SET hs_code=EXCLUDED.hs_code,description=EXCLUDED.description,country_of_origin=EXCLUDED.country_of_origin,quantity=EXCLUDED.quantity,unit_value=EXCLUDED.unit_value,declared_value=EXCLUDED.declared_value,currency=EXCLUDED.currency,duty_rate=EXCLUDED.duty_rate,tax_rate=EXCLUDED.tax_rate",
            id,TenantContext.getTenantId(),r.declarationId(),r.lineNo(),r.hsCode(),r.description(),r.countryOfOrigin(),r.quantity(),r.unitValue(),r.declaredValue(),r.currency(),r.dutyRate(),r.taxRate());
        return one("SELECT * FROM customs_declaration_lines WHERE declaration_id=? AND line_no=?",r.declarationId(),r.lineNo());
    }

    @Transactional
    public Map<String,Object> supplierBill(SupplierBillRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO finance_supplier_bills(id,tenant_id,supplier_name,supplier_invoice_no,shipment_id,currency,amount,issue_date,due_date,notes) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,supplier_name,supplier_invoice_no) DO UPDATE SET amount=EXCLUDED.amount,due_date=EXCLUDED.due_date,notes=EXCLUDED.notes",
            id,TenantContext.getTenantId(),r.supplierName(),r.supplierInvoiceNo(),r.shipmentId(),normCurrency(r.currency()),r.amount(),r.issueDate(),r.dueDate(),r.notes());
        return one("SELECT * FROM finance_supplier_bills WHERE supplier_name=? AND supplier_invoice_no=?",r.supplierName(),r.supplierInvoiceNo());
    }

    @Transactional
    public Map<String,Object> bankTransaction(BankTransactionRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO finance_bank_transactions(id,tenant_id,bank_account,transaction_date,reference,amount,currency,direction) VALUES(?,?,?,?,?,?,?,?)",
            id,TenantContext.getTenantId(),r.bankAccount(),r.transactionDate(),r.reference(),r.amount(),normCurrency(r.currency()),norm(r.direction()));
        return one("SELECT * FROM finance_bank_transactions WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> reconcileBankTransaction(UUID transactionId) {
        Map<String,Object> tx = one("SELECT * FROM finance_bank_transactions WHERE id=?", transactionId);
        if ("MATCHED".equalsIgnoreCase(Objects.toString(tx.get("status"),""))) return tx;
        String reference = Objects.toString(tx.get("reference"), "");
        BigDecimal amount = dec(tx.get("amount"));
        Map<String,Object> invoice = null;
        if (!reference.isBlank()) {
            try {
                invoice = one("SELECT * FROM commercial_invoices WHERE invoice_no=? AND invoice_amount-amount_paid>=?", reference, amount);
            } catch (ResponseStatusException ignored) {}
        }
        if (invoice == null) {
            try {
                invoice = one("SELECT * FROM commercial_invoices WHERE currency=? AND invoice_amount-amount_paid=? ORDER BY due_date NULLS LAST LIMIT 1",
                    tx.get("currency"), amount);
            } catch (ResponseStatusException ignored) {}
        }
        if (invoice == null) {
            return Map.of("status","UNMATCHED","transaction",tx);
        }
        UUID matchId=UUID.randomUUID();
        db.update("INSERT INTO finance_reconciliation_matches(id,tenant_id,bank_transaction_id,invoice_id,matched_amount) VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,bank_transaction_id) DO UPDATE SET invoice_id=EXCLUDED.invoice_id,matched_amount=EXCLUDED.matched_amount,status='MATCHED'",
            matchId,TenantContext.getTenantId(),transactionId,invoice.get("id"),amount);
        db.update("UPDATE finance_bank_transactions SET matched_invoice_id=?,status='MATCHED' WHERE id=?",invoice.get("id"),transactionId);
        return one("SELECT * FROM finance_bank_transactions WHERE id=?",transactionId);
    }

    @Transactional
    public Map<String,Object> carrierPerformance(CarrierPerformanceRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO carrier_performance_events(id,tenant_id,carrier_id,carrier_name,shipment_id,event_type,planned_at,actual_at,variance_minutes,score,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id,TenantContext.getTenantId(),r.carrierId(),r.carrierName(),r.shipmentId(),norm(r.eventType()),r.plannedAt(),r.actualAt(),r.varianceMinutes(),r.score(),r.notes());
        return one("SELECT * FROM carrier_performance_events WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> feedback(FeedbackRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO customer_feedback(id,tenant_id,shipment_id,quote_id,rating,category,comment,contact_email) VALUES(?,?,?,?,?,?,?,?)",
            id,TenantContext.getTenantId(),r.shipmentId(),r.quoteId(),r.rating(),r.category(),r.comment(),r.contactEmail());
        return one("SELECT * FROM customer_feedback WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> webhook(WebhookRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO api_webhooks(id,tenant_id,event_type,endpoint_url,secret_hash) VALUES(?,?,?,?,encode(digest(?, 'sha256'),'hex')) ON CONFLICT(tenant_id,event_type,endpoint_url) DO UPDATE SET active=true",
            id,TenantContext.getTenantId(),norm(r.eventType()),r.endpointUrl(),r.secret()==null?"":r.secret());
        return one("SELECT * FROM api_webhooks WHERE event_type=? AND endpoint_url=?",norm(r.eventType()),r.endpointUrl());
    }

    @Transactional
    public Map<String,Object> mobileSync(MobileSyncRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO mobile_sync_queue(id,tenant_id,device_id,operation_id,entity_type,entity_id,payload_json) VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,device_id,operation_id) DO NOTHING",
            id,TenantContext.getTenantId(),r.deviceId(),r.operationId(),norm(r.entityType()),r.entityId(),r.payloadJson());
        return one("SELECT * FROM mobile_sync_queue WHERE device_id=? AND operation_id=?",r.deviceId(),r.operationId());
    }

    @Transactional
    public Map<String,Object> workflowRule(WorkflowRuleRequest r) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO workflow_rules(id,tenant_id,rule_code,event_type,condition_json,action_json) VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,rule_code) DO UPDATE SET event_type=EXCLUDED.event_type,condition_json=EXCLUDED.condition_json,action_json=EXCLUDED.action_json,active=true",
            id,TenantContext.getTenantId(),r.ruleCode(),r.eventType(),r.conditionJson(),r.actionJson());
        return one("SELECT * FROM workflow_rules WHERE rule_code=?",r.ruleCode());
    }

    @Transactional
    public Map<String,Object> signature(DocumentSignatureRequest r) {
        one("SELECT id FROM cargo_documents WHERE id=?",r.documentId());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO document_signature_requests(id,tenant_id,document_id,signer_name,signer_email) VALUES(?,?,?,?,?)",
            id,TenantContext.getTenantId(),r.documentId(),r.signerName(),r.signerEmail());
        return one("SELECT * FROM document_signature_requests WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> runAutomation(AutomationRequest r) {
        UUID id=UUID.randomUUID();
        Map<String,Object> data = new LinkedHashMap<>();
        if (r.entityId()!=null && "SHIPMENT".equalsIgnoreCase(r.eventType())) {
            data.put("readiness", readiness(r.entityId(), new ReadinessRequest("AUTOMATION")));
            data.put("exceptions", evaluateExceptions(r.entityId()));
        }
        String result = toJson(data);
        db.update("INSERT INTO workflow_runs(id,tenant_id,rule_code,entity_type,entity_id,result_json,completed_at) VALUES(?,?,?,?,?,?,now())",
            id,TenantContext.getTenantId(),"SYSTEM_"+norm(r.eventType()),norm(r.eventType()),r.entityId(),result);
        return Map.of("runId",id,"eventType",r.eventType(),"result",data);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> analytics() {
        Map<String,Object> a=new LinkedHashMap<>();
        a.put("shipments", one("SELECT count(*) total,count(*) FILTER(WHERE status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) active,count(*) FILTER(WHERE status IN ('DELIVERED','COMPLETED')) delivered,COALESCE(sum(amount_billed_to_client),0) revenue,COALESCE(sum(COALESCE(supplier_cost,0)+COALESCE(other_cost,0)+COALESCE(other_expenses,0)),0) cost,COALESCE(sum(COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)-COALESCE(other_expenses,0)),0) gross_profit FROM shipments"));
        a.put("receivables", one("SELECT count(*) invoices,COALESCE(sum(invoice_amount),0) invoiced,COALESCE(sum(amount_paid),0) collected,COALESCE(sum(invoice_amount-amount_paid),0) outstanding,COALESCE(sum(CASE WHEN due_date<CURRENT_DATE AND invoice_amount>amount_paid THEN invoice_amount-amount_paid ELSE 0 END),0) overdue FROM commercial_invoices"));
        a.put("modes", rows("SELECT transport_mode mode,count(*) count FROM shipments GROUP BY transport_mode ORDER BY count DESC"));
        a.put("customers", rows("SELECT client_name,COUNT(*) shipments,COALESCE(SUM(amount_billed_to_client),0) revenue FROM shipments WHERE client_name IS NOT NULL GROUP BY client_name ORDER BY revenue DESC LIMIT 20"));
        a.put("carriers", rows("SELECT carrier_name,COUNT(*) events,ROUND(AVG(score),2) average_score,SUM(CASE WHEN variance_minutes IS NOT NULL AND variance_minutes<=0 THEN 1 ELSE 0 END) on_time FROM carrier_performance_events WHERE carrier_name IS NOT NULL GROUP BY carrier_name ORDER BY average_score DESC NULLS LAST"));
        return a;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> integrations() {
        return rows("SELECT * FROM integration_registry ORDER BY display_name");
    }

    private Map<String,Object> profitability(UUID shipmentId) {
        return one("SELECT COALESCE(amount_billed_to_client,0) revenue,COALESCE(supplier_cost,0)+COALESCE(other_cost,0)+COALESCE(other_expenses,0) total_cost,COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)-COALESCE(other_expenses,0) gross_profit,CASE WHEN COALESCE(amount_billed_to_client,0)=0 THEN 0 ELSE ROUND((COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)-COALESCE(other_expenses,0))*100/NULLIF(amount_billed_to_client,0),2) END margin_percent FROM shipments WHERE id=?",shipmentId);
    }

    private Map<String,Object> findCustomerRateCard(UUID clientId,String lane,String mode) {
        if (clientId == null) return null;
        try {
            return db.queryForMap(
                "SELECT * FROM customer_rate_cards WHERE client_id=? AND mode=? AND active=true " +
                "AND (lane_code IS NULL OR lane_code=?) AND valid_from<=CURRENT_DATE " +
                "AND (valid_until IS NULL OR valid_until>=CURRENT_DATE) " +
                "ORDER BY (lane_code IS NOT NULL) DESC,valid_from DESC LIMIT 1",
                clientId,mode,blankToNull(lane));
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    private Map<String,Object> findPricingRule(UUID clientId,UUID carrierId,String lane,String mode) {
        try {
            return db.queryForMap("SELECT * FROM pricing_rules WHERE mode=? AND active=true AND (client_id IS NULL OR client_id=?) AND (carrier_id IS NULL OR carrier_id=?) AND (lane_code IS NULL OR lane_code=?) AND valid_from<=CURRENT_DATE AND (valid_until IS NULL OR valid_until>=CURRENT_DATE) ORDER BY (client_id IS NOT NULL) DESC,(carrier_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,priority ASC LIMIT 1",mode,clientId,carrierId,blankToNull(lane));
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    private Map<String,Object> findRateCard(UUID clientId,UUID carrierId,String lane,String mode) {
        try {
            return db.queryForMap("SELECT * FROM rate_cards WHERE transport_mode=? AND active=true AND (client_id IS NULL OR client_id=?) AND (carrier_id IS NULL OR carrier_id=?) AND (lane_code IS NULL OR lane_code=?) AND valid_from<=CURRENT_DATE AND (valid_until IS NULL OR valid_until>=CURRENT_DATE) ORDER BY (client_id IS NOT NULL) DESC,(carrier_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,valid_from DESC LIMIT 1",mode,clientId,carrierId,blankToNull(lane));
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    private Map<String,Object> exception(UUID shipmentId,String type,String severity,String description) {
        Long exists=db.queryForObject("SELECT count(*) FROM operational_exceptions WHERE shipment_id=? AND type=? AND status='OPEN'",Long.class,shipmentId,type);
        if (exists!=null && exists>0) return Map.of("type",type,"status","EXISTING");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO operational_exceptions(id,tenant_id,shipment_id,type,severity,status,description) VALUES(?,?,?,?,?,'OPEN',?)",id,TenantContext.getTenantId(),shipmentId,type,severity,description);
        return Map.of("id",id,"type",type,"severity",severity);
    }

    private Check check(String code,boolean ok,String message){ return new Check(code,ok,message); }
    private record Check(String code,boolean ok,String message) {}
    private void tenant(UUID shipmentId){ if(shipmentId==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"shipmentId is required"); one("SELECT id FROM shipments WHERE id=?",shipmentId); }
    private void ensureQuote(UUID id){ one("SELECT id FROM commercial_quotes WHERE id=?",id); }
    private void ensureDeclaration(UUID id){ one("SELECT id FROM customs_declarations WHERE id=?",id); }
    private Map<String,Object> one(String sql,Object... args){ try { return db.queryForMap(sql,args); } catch(EmptyResultDataAccessException e){ throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found"); } }
    private List<Map<String,Object>> rows(String sql,Object... args){ return db.queryForList(sql,args); }
    private long count(String sql,Object... args){ Long n=db.queryForObject(sql,Long.class,args); return n==null?0:n; }
    private static BigDecimal pct(BigDecimal value,BigDecimal percent){return value.multiply(percent).divide(BigDecimal.valueOf(100),4,RoundingMode.HALF_UP);}
    private static BigDecimal dec(Object o){return o instanceof BigDecimal b?b:(o==null?BigDecimal.ZERO:new BigDecimal(o.toString()));}
    private static BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static int nzInt(Integer v){return v==null?0:v;}
    private static String norm(String s){return s==null?null:s.trim().toUpperCase(Locale.ROOT);}
    private static String normCurrency(String s){String v=(s==null||s.isBlank())?"USD":s.trim().toUpperCase(Locale.ROOT);return v.length()>3?v.substring(0,3):v;}
    private static String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private String toJson(Object o){try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException("Unable to serialize workflow result",e);}}
}
