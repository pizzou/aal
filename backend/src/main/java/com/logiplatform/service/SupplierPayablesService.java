package com.logiplatform.service;

import com.logiplatform.repository.FinanceLedgerRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Canonical vendor-payable accounting: a supplier invoice is an expense and
 * payable; a later settlement clears payable and cash, never a second expense.
 * All records are tenant-scoped and server-issued, not client-supplied IDs.
 */
@Service
public class SupplierPayablesService {
    private final JdbcTemplate db;
    private final ShipmentRepository shipments;
    private final FinanceLedgerRepository ledger;
    private final FinancePostingService finance;

    public SupplierPayablesService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                   ShipmentRepository shipments,
                                   FinanceLedgerRepository ledger,
                                   FinancePostingService finance) {
        this.db = db;
        this.shipments = shipments;
        this.ledger = ledger;
        this.finance = finance;
    }

    public record BillRequest(UUID shipmentId, String supplierName, String vendorInvoiceReference,
                              BigDecimal amount, String currency, LocalDate billDate,
                              LocalDate dueDate, String description) {}
    public record PaymentRequest(BigDecimal amount, String currency,
                                 String paymentMethod, String paymentReference,
                                 String idempotencyKey) {}
    public record Bill(UUID id, UUID shipmentId, String supplierName, String vendorInvoiceReference,
                       BigDecimal amount, BigDecimal amountPaid, BigDecimal balance,
                       String currency, LocalDate billDate, LocalDate dueDate,
                       String status, String description, UUID createdBy,
                       UUID approvedBy, Instant approvedAt) {}
    public record Payment(UUID id, UUID billId, BigDecimal amount, String currency,
                          String paymentMethod, String paymentReference,
                          String idempotencyKey, Instant paidAt) {}

    private static final org.springframework.jdbc.core.RowMapper<Bill> BILL_MAPPER = (rs, n) -> {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal paid = rs.getBigDecimal("amount_paid");
        return new Bill((UUID) rs.getObject("id"), (UUID) rs.getObject("shipment_id"),
                rs.getString("supplier_name"), rs.getString("vendor_invoice_reference"),
                amount, paid, amount.subtract(paid), rs.getString("currency"),
                rs.getObject("bill_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("status"), rs.getString("description"),
                (UUID) rs.getObject("created_by"), (UUID) rs.getObject("approved_by"),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant());
    };
    private static final org.springframework.jdbc.core.RowMapper<Payment> PAYMENT_MAPPER = (rs, n) ->
            new Payment((UUID) rs.getObject("id"), (UUID) rs.getObject("supplier_bill_id"),
                    rs.getBigDecimal("amount"), rs.getString("currency"),
                    rs.getString("payment_method"), rs.getString("payment_reference"),
                    rs.getString("idempotency_key"), rs.getTimestamp("paid_at").toInstant());

    @Transactional
    public Bill create(BillRequest request) {
        if (request == null || request.shipmentId() == null || request.billDate() == null)
            throw bad("Shipment and supplier bill date are required");
        String supplier = required(request.supplierName(), "Supplier name", 200);
        String reference = required(request.vendorInvoiceReference(), "Supplier invoice reference", 120).toUpperCase(Locale.ROOT);
        String currency = required(request.currency(), "Currency", 10).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw bad("Use a valid 3-letter ISO currency code");
        positive(request.amount());
        if (request.dueDate() != null && request.dueDate().isBefore(request.billDate()))
            throw bad("Due date cannot precede bill date");
        if (request.description() != null && request.description().length() > 4000)
            throw bad("Description is too long");
        UUID tenant = TenantContext.getTenantId();
        // Shared shipment-row lock serializes registration with normal shipment changes.
        var shipment = shipments.findLockedByIdAndTenantId(request.shipmentId(), tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        String shipmentCurrency = shipment.getCurrency() == null || shipment.getCurrency().isBlank()
                ? "USD" : shipment.getCurrency().trim().toUpperCase(Locale.ROOT);
        if (!shipmentCurrency.equals(currency))
            throw bad("Supplier bill currency must match shipment currency until approved FX conversion is available");
        preventMixingLegacySupplierCash(tenant, request.shipmentId(), currency);
        UUID id = UUID.randomUUID();
        try {
            db.update("""
                INSERT INTO supplier_bills (id,tenant_id,shipment_id,supplier_name,supplier_key,
                  vendor_invoice_reference,currency,amount,bill_date,due_date,description,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, tenant, request.shipmentId(), supplier,
                    supplier.toUpperCase(Locale.ROOT), reference, currency,
                    request.amount(), request.billDate(), request.dueDate(),
                    request.description(), actor());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This supplier invoice reference is already registered for this supplier", ex);
        }
        return findBill(tenant, id, false);
    }

    @Transactional
    public Bill approve(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        Bill bill = findBill(tenant, id, true);
        if ("APPROVED".equals(bill.status())) return bill;
        UUID approver = actor();
        if (approver.equals(bill.createdBy()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A supplier bill must be approved by someone other than its preparer");
        // Prevent a previously posted legacy cash expense from becoming a second cost.
        preventMixingLegacySupplierCash(tenant, bill.shipmentId(), bill.currency());
        db.update("""
                UPDATE supplier_bills SET status='APPROVED', approved_by=?, approved_at=now()
                WHERE tenant_id=? AND id=? AND status='DRAFT'
                """, approver, tenant, id);
        // Ledger insertion and approval commit atomically. Closed period trigger applies.
        finance.postSupplierBill(tenant, id, bill.amount(), bill.currency(), bill.vendorInvoiceReference());
        return findBill(tenant, id, false);
    }

    @Transactional
    public Payment settle(UUID id, PaymentRequest request) {
        if (request == null) throw bad("Payment details are required");
        positive(request.amount());
        String c = required(request.currency(), "Currency", 10).toUpperCase(Locale.ROOT);
        String method = required(request.paymentMethod(), "Payment method", 40).toUpperCase(Locale.ROOT);
        String reference = required(request.paymentReference(), "Payment evidence/reference", 200);
        String key = required(request.idempotencyKey(), "Idempotency key", 160);
        UUID tenant = TenantContext.getTenantId();
        Bill bill = findBill(tenant, id, true);
        // Check the unique idempotency key even if the bill is now paid in full.
        List<Payment> prior = db.query("""
                SELECT * FROM supplier_bill_payments WHERE tenant_id=? AND idempotency_key=?
                """, PAYMENT_MAPPER, tenant, key);
        if (!prior.isEmpty()) {
            Payment p = prior.get(0);
            if (!p.billId().equals(id) || p.amount().compareTo(request.amount()) != 0 ||
                !p.currency().equals(c) || !p.paymentMethod().equals(method) ||
                !p.paymentReference().equals(reference))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key was already used for a different supplier payment");
            return p;
        }
        if (!"APPROVED".equals(bill.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approve supplier bill before paying");
        if (!bill.currency().equals(c)) throw bad("Supplier payment currency must match bill currency");
        if (request.amount().compareTo(bill.balance()) > 0)
            throw bad("Payment exceeds outstanding supplier balance");
        UUID paymentId = UUID.randomUUID();
        db.update("""
                INSERT INTO supplier_bill_payments
                  (id,tenant_id,supplier_bill_id,amount,currency,payment_method,payment_reference,idempotency_key,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, paymentId, tenant, id, request.amount(), c, method, reference, key, actor());
        db.update("UPDATE supplier_bills SET amount_paid=amount_paid+? WHERE tenant_id=? AND id=?",
                request.amount(), tenant, id);
        finance.postSupplierBillPayment(tenant, paymentId, request.amount(), c, reference);
        return payment(tenant, paymentId);
    }

    @Transactional(readOnly = true)
    public List<Bill> list(UUID shipmentId) {
        UUID tenant = TenantContext.getTenantId();
        if (shipmentId == null) {
            return db.query("SELECT * FROM supplier_bills WHERE tenant_id=? ORDER BY created_at DESC LIMIT 500",
                    BILL_MAPPER, tenant);
        }
        return db.query("SELECT * FROM supplier_bills WHERE tenant_id=? AND shipment_id=? ORDER BY created_at DESC LIMIT 500",
                BILL_MAPPER, tenant, shipmentId);
    }

    @Transactional(readOnly = true)
    public List<Payment> payments(UUID billId) {
        UUID tenant = TenantContext.getTenantId();
        findBill(tenant, billId, false);
        return db.query("""
            SELECT * FROM supplier_bill_payments WHERE tenant_id=? AND supplier_bill_id=? ORDER BY paid_at,id
            """, PAYMENT_MAPPER, tenant, billId);
    }

    private Bill findBill(UUID tenant, UUID id, boolean lock) {
        List<Bill> records = db.query("SELECT * FROM supplier_bills WHERE tenant_id=? AND id=?" +
                (lock ? " FOR UPDATE" : ""), BILL_MAPPER, tenant, id);
        if (records.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier bill not found");
        return records.get(0);
    }
    private Payment payment(UUID tenant, UUID id) {
        return db.queryForObject("SELECT * FROM supplier_bill_payments WHERE tenant_id=? AND id=?",
                PAYMENT_MAPPER, tenant, id);
    }
    private void preventMixingLegacySupplierCash(UUID tenant, UUID shipmentId, String currency) {
        boolean legacy = ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(
                tenant, "SUPPLIER_PAYMENT", shipmentId).stream()
                .anyMatch(entry -> "DEBIT".equals(entry.getEntryType()) && entry.getAmount().signum() > 0);
        if (legacy)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This shipment already has supplier cash expensed through the legacy workflow. " +
                    "Reconcile that posting before converting the shipment to accrued supplier bills.");
    }
    private static void positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 4)
            throw bad("Amount must be positive with no more than four decimals");
    }
    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw bad(label + " is required (maximum " + max + " characters)");
        return value.trim();
    }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static UUID actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof TenantPrincipal p && p.userId() != null) return p.userId();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated staff account required");
    }
}
