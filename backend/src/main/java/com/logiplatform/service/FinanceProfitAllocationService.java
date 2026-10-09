package com.logiplatform.service;

import com.logiplatform.model.FinanceBankDestination;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.FinanceBankDestinationRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Creates auditable allocation instructions from AAL's canonical shipment net-income fields.
 * This service does not initiate, queue, or claim a real bank transfer.
 */
@Service
public class FinanceProfitAllocationService {
    private static final BigDecimal HUNDRED = new BigDecimal("100.0000");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");
    private static final String BASIS = "Billed revenue minus supplier payments and other expenses (AAL net-income formula)";

    private final JdbcTemplate db;
    private final ShipmentRepository shipments;
    private final FinanceBankDestinationRepository banks;

    public FinanceProfitAllocationService(
            @org.springframework.beans.factory.annotation.Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ShipmentRepository shipments,
            FinanceBankDestinationRepository banks) {
        this.db = db;
        this.shipments = shipments;
        this.banks = banks;
    }

    @Transactional(readOnly = true)
    public List<RuleView> rules() {
        UUID tenant = requireTenant();
        return db.query("""
            SELECT r.id, r.bank_destination_id, b.code, b.name, b.account_reference,
                   r.percentage, r.active, r.created_at
              FROM finance_profit_allocation_rules r
              JOIN finance_bank_destinations b
                ON b.id=r.bank_destination_id AND b.tenant_id=r.tenant_id
             WHERE r.tenant_id=?
             ORDER BY b.name
            """, (rs, row) -> new RuleView(
                rs.getObject("id", UUID.class),
                rs.getObject("bank_destination_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("account_reference"),
                rs.getBigDecimal("percentage"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()), tenant);
    }

    @Transactional
    public List<RuleView> saveRules(List<RuleInput> requested) {
        UUID tenant = requireTenant();
        if (requested == null || requested.isEmpty()) {
            throw badRequest("At least one bank allocation rule is required");
        }
        if (requested.size() > 20) throw badRequest("No more than 20 profit allocation destinations may be configured");

        Set<UUID> unique = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (RuleInput input : requested) {
            if (input == null || input.bankDestinationId() == null || input.percentage() == null) {
                throw badRequest("Each profit rule requires a bank destination and percentage");
            }
            if (!unique.add(input.bankDestinationId())) throw badRequest("A bank destination may appear only once");
            if (input.percentage().signum() < 0 || input.percentage().compareTo(HUNDRED) > 0) {
                throw badRequest("Each profit share must be between 0 and 100 percent");
            }
            total = total.add(input.percentage().setScale(4, RoundingMode.HALF_UP));
            FinanceBankDestination bank = banks.findByTenantIdAndId(tenant, input.bankDestinationId())
                    .filter(FinanceBankDestination::isActive)
                    .orElseThrow(() -> notFound("An active bank destination was not found in this tenant"));
        }
        if (total.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
            throw badRequest("Active profit allocation rules must total exactly 100%");
        }

        // Keep rules not in the latest configuration inactive instead of deleting records.
        db.update("UPDATE finance_profit_allocation_rules SET active=false, updated_at=now() WHERE tenant_id=?", tenant);
        for (RuleInput input : requested) {
            db.update("""
                INSERT INTO finance_profit_allocation_rules
                    (id, tenant_id, bank_destination_id, percentage, active, created_at, updated_at)
                VALUES (?,?,?,?,true,now(),now())
                ON CONFLICT (tenant_id, bank_destination_id)
                DO UPDATE SET percentage=EXCLUDED.percentage, active=true, updated_at=now()
                """, UUID.randomUUID(), tenant, input.bankDestinationId(),
                    input.percentage().setScale(4, RoundingMode.HALF_UP));
        }
        return rules();
    }

    @Transactional
    public AllocationResult allocateShipment(UUID shipmentId) {
        UUID tenant = requireTenant();
        Shipment shipment = shipments.findByIdAndTenantId(shipmentId, tenant)
                .orElseThrow(() -> notFound("Shipment not found"));

        BigDecimal revenue = nz(shipment.getAmountBilledToClient(), shipment.getClientRevenue());
        if (revenue == null || revenue.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Shipment has no positive billed revenue; invoice it before allocating profit");
        }
        BigDecimal supplierPaid = nz(shipment.getAmountPaidToSupply(), BigDecimal.ZERO);
        BigDecimal otherExpenses = nz(shipment.getOtherExpenses(), BigDecimal.ZERO);
        BigDecimal netProfit = revenue.subtract(supplierPaid).subtract(otherExpenses).setScale(4, RoundingMode.HALF_UP);
        if (netProfit.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Shipment net income is not positive; no profit distribution instructions were created");
        }
        String currency = shipment.getCurrency() == null || shipment.getCurrency().isBlank()
                ? "USD" : shipment.getCurrency().trim().toUpperCase(Locale.ROOT);

        List<ActiveRule> activeRules = db.query("""
            SELECT r.bank_destination_id, b.code, b.name, b.account_reference, r.percentage
              FROM finance_profit_allocation_rules r
              JOIN finance_bank_destinations b
                ON b.id=r.bank_destination_id AND b.tenant_id=r.tenant_id
             WHERE r.tenant_id=? AND r.active=true AND b.active=true
             ORDER BY b.name, b.code
            """, (rs, row) -> new ActiveRule(
                rs.getObject("bank_destination_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("account_reference"), rs.getBigDecimal("percentage")), tenant);
        if (activeRules.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Configure profit allocation banks and percentages before creating a distribution");
        BigDecimal percentTotal = activeRules.stream().map(ActiveRule::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (percentTotal.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Active profit allocation rules must total exactly 100%");
        }

        // Repeating the same allocation request must not create a second set of
        // payout instructions. The fingerprint intentionally uses the shipment,
        // currency and net profit, not mutable bank configuration.
        String fingerprint = allocationFingerprint(tenant, shipment.getId(), currency, netProfit);
        UUID runId = UUID.randomUUID();
        int insertedRun = db.update("""
            INSERT INTO finance_profit_allocation_runs
              (id, tenant_id, shipment_id, shipment_reference, currency, revenue,
               supplier_paid, other_expenses, net_profit, profit_basis, transfer_status,
               calculation_fingerprint, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?, 'INSTRUCTIONS_ONLY', ?, now())
            ON CONFLICT (tenant_id, shipment_id, calculation_fingerprint) DO NOTHING
            """, runId, tenant, shipment.getId(), shipment.getReferenceCode(), currency,
                revenue, supplierPaid, otherExpenses, netProfit, BASIS, fingerprint);
        if (insertedRun == 0) {
            AllocationResult existing = findExistingAllocation(tenant, fingerprint);
            if (existing != null) return existing;
            throw new IllegalStateException("Existing profit allocation snapshot could not be loaded");
        }

        List<ProfitAllocationItem> items = new ArrayList<>();
        BigDecimal allocatedBeforeLast = BigDecimal.ZERO;
        for (int index = 0; index < activeRules.size(); index++) {
            ActiveRule rule = activeRules.get(index);
            BigDecimal amount;
            if (index == activeRules.size() - 1) {
                // Assign the rounding residual to the last destination so the
                // recorded distribution always equals the audited profit basis.
                amount = netProfit.subtract(allocatedBeforeLast).setScale(4, RoundingMode.HALF_UP);
            } else {
                amount = netProfit.multiply(rule.percentage()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                allocatedBeforeLast = allocatedBeforeLast.add(amount);
            }
            db.update("""
                INSERT INTO finance_profit_allocation_items
                  (id, tenant_id, allocation_run_id, bank_destination_id, bank_code, bank_name,
                   account_reference, percentage, allocated_amount, currency, transfer_status, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'NOT_SENT', now())
                """, UUID.randomUUID(), tenant, runId, rule.bankId(), rule.bankCode(), rule.bankName(),
                    rule.accountReference(), rule.percentage(), amount, currency);
            items.add(new ProfitAllocationItem(rule.bankId(), rule.bankCode(), rule.bankName(),
                    rule.accountReference(), rule.percentage(), amount, currency, "NOT_SENT"));
        }
        return new AllocationResult(runId, shipment.getId(), shipment.getReferenceCode(), currency,
                revenue, supplierPaid, otherExpenses, netProfit, BASIS, "INSTRUCTIONS_ONLY", items);
    }

    @Transactional(readOnly = true)
    public List<RunSummary> recentRuns() {
        UUID tenant = requireTenant();
        return db.query("""
            SELECT id, shipment_id, shipment_reference, currency, revenue, supplier_paid,
                   other_expenses, net_profit, transfer_status, created_at
              FROM finance_profit_allocation_runs
             WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50
            """, (rs, row) -> new RunSummary(
                rs.getObject("id", UUID.class), rs.getObject("shipment_id", UUID.class),
                rs.getString("shipment_reference"), rs.getString("currency"),
                rs.getBigDecimal("revenue"), rs.getBigDecimal("supplier_paid"),
                rs.getBigDecimal("other_expenses"), rs.getBigDecimal("net_profit"),
                rs.getString("transfer_status"), rs.getTimestamp("created_at").toInstant()), tenant);
    }

    private AllocationResult findExistingAllocation(UUID tenant, String fingerprint) {
        List<AllocationHeader> headers = db.query("""
            SELECT id, shipment_id, shipment_reference, currency, revenue, supplier_paid,
                   other_expenses, net_profit, profit_basis, transfer_status
              FROM finance_profit_allocation_runs
             WHERE tenant_id=? AND calculation_fingerprint=?
             LIMIT 1
            """, (rs, row) -> new AllocationHeader(
                rs.getObject("id", UUID.class), rs.getObject("shipment_id", UUID.class),
                rs.getString("shipment_reference"), rs.getString("currency"),
                rs.getBigDecimal("revenue"), rs.getBigDecimal("supplier_paid"),
                rs.getBigDecimal("other_expenses"), rs.getBigDecimal("net_profit"),
                rs.getString("profit_basis"), rs.getString("transfer_status")), tenant, fingerprint);
        if (headers.isEmpty()) return null;
        AllocationHeader h = headers.get(0);
        List<ProfitAllocationItem> items = db.query("""
            SELECT bank_destination_id, bank_code, bank_name, account_reference,
                   percentage, allocated_amount, currency, transfer_status
              FROM finance_profit_allocation_items
             WHERE tenant_id=? AND allocation_run_id=?
             ORDER BY bank_name, bank_code
            """, (rs, row) -> new ProfitAllocationItem(
                rs.getObject("bank_destination_id", UUID.class), rs.getString("bank_code"),
                rs.getString("bank_name"), rs.getString("account_reference"),
                rs.getBigDecimal("percentage"), rs.getBigDecimal("allocated_amount"),
                rs.getString("currency"), rs.getString("transfer_status")), tenant, h.runId());
        return new AllocationResult(h.runId(), h.shipmentId(), h.shipmentReference(), h.currency(),
                h.revenue(), h.supplierPaid(), h.otherExpenses(), h.netProfit(), h.profitBasis(),
                h.transferStatus(), items);
    }

    private static String allocationFingerprint(UUID tenant, UUID shipment, String currency, BigDecimal netProfit) {
        String canonical = tenant + "|" + shipment + "|" + currency + "|" + netProfit.setScale(4, RoundingMode.HALF_UP).toPlainString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable for profit allocation idempotency", e);
        }
    }

    private record AllocationHeader(UUID runId, UUID shipmentId, String shipmentReference,
            String currency, BigDecimal revenue, BigDecimal supplierPaid, BigDecimal otherExpenses,
            BigDecimal netProfit, String profitBasis, String transferStatus) {}

    private static UUID requireTenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is required");
        return tenant;
    }
    private static BigDecimal nz(BigDecimal value, BigDecimal fallback) { return value == null ? fallback : value; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    public record RuleInput(UUID bankDestinationId, BigDecimal percentage) {}
    public record RuleView(UUID id, UUID bankDestinationId, String bankCode, String bankName,
                           String accountReference, BigDecimal percentage, boolean active, java.time.Instant createdAt) {}
    private record ActiveRule(UUID bankId, String bankCode, String bankName, String accountReference, BigDecimal percentage) {}
    public record ProfitAllocationItem(UUID bankDestinationId, String bankCode, String bankName,
                                       String accountReference, BigDecimal percentage, BigDecimal amount,
                                       String currency, String transferStatus) {}
    public record AllocationResult(UUID runId, UUID shipmentId, String shipmentReference, String currency,
                                   BigDecimal revenue, BigDecimal supplierPaid, BigDecimal otherExpenses,
                                   BigDecimal netProfit, String profitBasis, String transferStatus,
                                   List<ProfitAllocationItem> allocations) {}
    public record RunSummary(UUID runId, UUID shipmentId, String shipmentReference, String currency,
                             BigDecimal revenue, BigDecimal supplierPaid, BigDecimal otherExpenses,
                             BigDecimal netProfit, String transferStatus, java.time.Instant createdAt) {}
}
