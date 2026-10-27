package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finance verification for replacing shipment spreadsheets.
 * No inferred historical payments, no FX conversion, no false PASS on counts.
 * Excludes unissued invoice drafts and computes balances per original currency.
 */
@Service
public class ClientCutoverReconciliationService {
    private final JdbcTemplate db;
    public ClientCutoverReconciliationService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    public record CurrencyCheck(String currency, BigDecimal issuedInvoiceOutstanding,
                                BigDecimal receivableInLedger, BigDecimal receivableDifference,
                                BigDecimal supplierBillsOutstanding, BigDecimal payableInLedger,
                                BigDecimal payableDifference, BigDecimal debitTotal,
                                BigDecimal creditTotal, BigDecimal journalDifference,
                                BigDecimal customerCashReceipts, BigDecimal supplierBillPayments,
                                BigDecimal legacySupplierCashExpenses, String status) {}
    public record Snapshot(LocalDate asOf, String status, List<CurrencyCheck> byCurrency,
                           String caveat) {}

    /** Transaction-based receipt/settlement register, NOT a bank-statement reconciliation. */
    public record CashMovement(String source, Instant recordedAt, String party,
                               String documentReference, String shipmentReference,
                               BigDecimal amount, String currency, String paymentMethod,
                               String paymentReference) {}

    @Transactional(readOnly = true)
    public List<CashMovement> paymentRegister(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Kigali"));
        LocalDate startDate = from == null ? today.withDayOfMonth(1) : from;
        LocalDate endDate = to == null ? today : to;
        if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) > 365)
            throw new IllegalArgumentException("Date range must be ordered and no longer than one year");
        var timezone = ZoneId.of("Africa/Kigali");
        java.sql.Timestamp start = java.sql.Timestamp.from(startDate.atStartOfDay(timezone).toInstant());
        java.sql.Timestamp exclusiveEnd = java.sql.Timestamp.from(endDate.plusDays(1).atStartOfDay(timezone).toInstant());
        UUID tenant = TenantContext.getTenantId();
        return db.query("""
            SELECT source, recorded_at, party, document_ref, shipment_ref, amount, currency, payment_method, payment_ref
              FROM (
               SELECT 'CUSTOMER_RECEIPT' AS source, p.created_at AS recorded_at,
                      COALESCE(i.client,'') AS party, i.invoice_no AS document_ref,
                      COALESCE(s.reference_code,'') AS shipment_ref,
                      p.amount, p.currency, p.payment_method, COALESCE(p.reference,'') AS payment_ref
                 FROM commercial_payments p
                 JOIN commercial_invoices i ON i.id=p.invoice_id AND i.tenant_id=p.tenant_id
                 LEFT JOIN shipments s ON s.id=i.shipment_id AND s.tenant_id=i.tenant_id
                WHERE p.tenant_id=? AND p.created_at >= ? AND p.created_at < ?
               UNION ALL
               SELECT 'SUPPLIER_SETTLEMENT' AS source, p.paid_at AS recorded_at,
                      b.supplier_name AS party, b.vendor_invoice_reference AS document_ref,
                      COALESCE(s.reference_code,'') AS shipment_ref,
                      p.amount, p.currency, p.payment_method, p.payment_reference AS payment_ref
                 FROM supplier_bill_payments p
                 JOIN supplier_bills b ON b.id=p.supplier_bill_id AND b.tenant_id=p.tenant_id
                 JOIN shipments s ON s.id=b.shipment_id AND s.tenant_id=b.tenant_id
                WHERE p.tenant_id=? AND p.paid_at >= ? AND p.paid_at < ?
              ) x ORDER BY recorded_at DESC LIMIT 2000
            """, (rs,n) -> new CashMovement(rs.getString("source"),
                    rs.getTimestamp("recorded_at").toInstant(), rs.getString("party"),
                    rs.getString("document_ref"), rs.getString("shipment_ref"),
                    rs.getBigDecimal("amount"), rs.getString("currency"),
                    rs.getString("payment_method"), rs.getString("payment_ref")),
                tenant, start, exclusiveEnd, tenant, start, exclusiveEnd);
    }

    @Transactional(readOnly = true)
    public Snapshot current() {
        UUID tenant = TenantContext.getTenantId();
        List<String> currencies = db.query("""
            SELECT DISTINCT currency FROM (
              SELECT currency FROM commercial_invoices WHERE tenant_id=?
              UNION ALL SELECT currency FROM supplier_bills WHERE tenant_id=?
              UNION ALL SELECT currency FROM finance_ledger_entries WHERE tenant_id=?
            ) x WHERE currency IS NOT NULL ORDER BY currency
            """, (rs, n) -> rs.getString(1), tenant, tenant, tenant);
        List<CurrencyCheck> result = new ArrayList<>();
        boolean allMatch = true;
        for (String c : currencies) {
            BigDecimal ar = value("""
                SELECT COALESCE(SUM(invoice_amount + debit_note_amount - credit_note_amount - amount_paid),0)
                FROM commercial_invoices WHERE tenant_id=? AND currency=?
                AND lifecycle_status NOT IN ('DRAFT','CANCELLED','VOID','REVERSED')
                """, tenant, c);
            BigDecimal arLedger = account(tenant, c, FinancePostingService.ACCOUNTS_RECEIVABLE, "DEBIT")
                    .subtract(account(tenant, c, FinancePostingService.ACCOUNTS_RECEIVABLE, "CREDIT"));
            BigDecimal ap = value("""
                SELECT COALESCE(SUM(amount - amount_paid),0) FROM supplier_bills
                WHERE tenant_id=? AND currency=? AND status='APPROVED'
                """, tenant, c);
            BigDecimal apLedger = account(tenant, c, FinancePostingService.ACCOUNTS_PAYABLE, "CREDIT")
                    .subtract(account(tenant, c, FinancePostingService.ACCOUNTS_PAYABLE, "DEBIT"));
            BigDecimal dr = value("SELECT COALESCE(SUM(amount),0) FROM finance_ledger_entries WHERE tenant_id=? AND currency=? AND entry_type='DEBIT'", tenant,c);
            BigDecimal cr = value("SELECT COALESCE(SUM(amount),0) FROM finance_ledger_entries WHERE tenant_id=? AND currency=? AND entry_type='CREDIT'", tenant,c);
            BigDecimal receipts = value("SELECT COALESCE(SUM(amount),0) FROM commercial_payments WHERE tenant_id=? AND currency=?",tenant,c);
            BigDecimal settlements = value("SELECT COALESCE(SUM(amount),0) FROM supplier_bill_payments WHERE tenant_id=? AND currency=?",tenant,c);
            BigDecimal legacy = value("""
                SELECT COALESCE(SUM(amount),0) FROM finance_ledger_entries
                WHERE tenant_id=? AND currency=? AND source_type='SUPPLIER_PAYMENT' AND entry_type='DEBIT'
                """,tenant,c);
            BigDecimal arDiff = ar.subtract(arLedger);
            BigDecimal apDiff = ap.subtract(apLedger);
            BigDecimal journalDiff = dr.subtract(cr);
            boolean matched = arDiff.signum() == 0 && apDiff.signum() == 0 && journalDiff.signum() == 0;
            allMatch &= matched;
            result.add(new CurrencyCheck(c, ar, arLedger, arDiff, ap, apLedger, apDiff,
                    dr, cr, journalDiff, receipts, settlements, legacy,
                    matched ? "MATCHED_NOT_CERTIFIED" : "DIFFERENCE_REQUIRES_REVIEW"));
        }
        return new Snapshot(LocalDate.now(ZoneId.of("Africa/Kigali")),
                result.isEmpty() ? "NO_LEDGER_DATA" : allMatch ? "MATCHED_NOT_CERTIFIED" : "DIFFERENCES",
                List.copyOf(result),
                "A matching balance is not a signed cutover or proof of bank receipts. Historical Excel rows " +
                "lack dated cash transactions and payment evidence. Compare original customer invoices, bank " +
                "statements and supplier invoices separately before ending spreadsheet operations.");
    }

    private BigDecimal account(UUID tenant, String currency, String account, String type) {
        return value("""
                SELECT COALESCE(SUM(amount),0) FROM finance_ledger_entries
                WHERE tenant_id=? AND currency=? AND account_code=? AND entry_type=?
                """, tenant, currency, account, type);
    }
    private BigDecimal value(String sql, Object... args) {
        BigDecimal amount = db.queryForObject(sql, BigDecimal.class, args);
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
