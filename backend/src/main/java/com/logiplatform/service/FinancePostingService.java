package com.logiplatform.service;

import com.logiplatform.model.FinanceLedgerEntry;
import com.logiplatform.repository.FinanceLedgerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Single accounting posting boundary. All financial events use balanced
 * debit/credit pairs and carry a source identity where the event can be
 * retried.
 */
@Service
public class FinancePostingService {

    public static final String CASH = "1000-CASH";
    public static final String ACCOUNTS_RECEIVABLE = "1100-ACCOUNTS_RECEIVABLE";
    public static final String FREIGHT_REVENUE = "4000-FREIGHT_REVENUE";
    public static final String OPERATING_EXPENSE = "5100-OPERATING_EXPENSE";
    public static final String TAX_PAYABLE = "2200-TAX_PAYABLE";
    public static final String ACCOUNTS_PAYABLE = "2100-ACCOUNTS_PAYABLE";

    private final FinanceLedgerRepository ledger;

    /**
     * PostgreSQL transaction-scoped lock serializes retries for the same business
     * event.
     */
    @PersistenceContext
    private EntityManager entityManager;

    public FinancePostingService(FinanceLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public void postInvoice(UUID tenantId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(invoiceId, "invoiceId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "INVOICE", invoiceId, "ALL");
        if (!sourceEntries(tenantId, "INVOICE_REVERSAL", invoiceId).isEmpty()) {
            throw new IllegalStateException("A reversed invoice cannot be posted again under the same invoice ID");
        }
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "INVOICE", invoiceId);
        if (!existing.isEmpty()) {
            if (isExactSimpleInvoice(existing, amount, c))
                return;
            throw new IllegalStateException(
                    "Invoice already has a different accounting posting; use a credit/debit note or reversal");
        }
        postBalanced(tenantId, invoiceId, "INVOICE", invoiceId, amount, c,
                ACCOUNTS_RECEIVABLE, FREIGHT_REVENUE, "Freight invoice " + invoiceNo);
    }

    /**
     * Posts a tax-bearing customer invoice as accounts receivable, net revenue,
     * and tax payable. It is intentionally only for an invoice that has not
     * already been posted; an existing posting is corrected using notes/reversals.
     */
    @Transactional
    public void postInvoiceWithTax(UUID tenantId, UUID invoiceId, BigDecimal grossAmount,
            BigDecimal taxAmount, String currency, String invoiceNo) {
        validateTenant(tenantId);
        validateAmount(grossAmount);
        if (taxAmount == null || taxAmount.signum() < 0 || taxAmount.compareTo(grossAmount) > 0) {
            throw new IllegalArgumentException("Tax amount must be between zero and invoice total");
        }
        validateSourceId(invoiceId, "invoiceId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "INVOICE", invoiceId, "ALL");
        if (!sourceEntries(tenantId, "INVOICE_REVERSAL", invoiceId).isEmpty()) {
            throw new IllegalStateException("A reversed invoice cannot be posted again under the same invoice ID");
        }
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "INVOICE", invoiceId);
        if (!existing.isEmpty()) {
            if (isExactTaxInvoice(existing, grossAmount, taxAmount, c))
                return;
            throw new IllegalStateException("Invoice already has a different accounting posting; do not re-post tax");
        }
        BigDecimal revenue = grossAmount.subtract(taxAmount);
        ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, "INVOICE", invoiceId,
                "DEBIT", ACCOUNTS_RECEIVABLE, grossAmount, c, "Freight invoice " + invoiceNo));
        if (revenue.signum() > 0) {
            ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, "INVOICE", invoiceId,
                    "CREDIT", FREIGHT_REVENUE, revenue, c, "Freight revenue for invoice " + invoiceNo));
        }
        if (taxAmount.signum() > 0) {
            ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, "INVOICE", invoiceId,
                    "CREDIT", TAX_PAYABLE, taxAmount, c, "Tax payable for invoice " + invoiceNo));
        }
    }

    /**
     * Reverses every original journal line for an unpaid invoice. The reversal
     * is a new immutable journal event; no posted ledger line is edited or deleted.
     */
    @Transactional
    public void reverseInvoice(UUID tenantId, UUID invoiceId, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateSourceId(invoiceId, "invoiceId");
        lockPosting(tenantId, "INVOICE", invoiceId, "ALL");
        // The source may be in any currency; check all immutable reversal rows.
        List<FinanceLedgerEntry> priorReversal = ledger
                .findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenantId, "INVOICE_REVERSAL", invoiceId);
        if (!priorReversal.isEmpty())
            return;
        List<FinanceLedgerEntry> originals = ledger
                .findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenantId, "INVOICE", invoiceId);
        if (originals.isEmpty())
            return; // A draft has no journal to reverse.
        java.util.List<FinanceLedgerEntry> entries = new java.util.ArrayList<>();
        String suffix = (reason == null || reason.isBlank()) ? "" : " / " + reason.trim();
        for (FinanceLedgerEntry entry : originals) {
            String inverseType = "DEBIT".equalsIgnoreCase(entry.getEntryType()) ? "CREDIT" : "DEBIT";
            entries.add(new FinanceLedgerEntry(tenantId, invoiceId, "INVOICE_REVERSAL", invoiceId,
                    inverseType, entry.getAccountCode(), entry.getAmount(), entry.getCurrency(),
                    "Reversal of invoice " + invoiceNo + suffix));
        }
        ledger.saveAll(entries);
    }

    @Transactional
    public void postCustomerPayment(UUID tenantId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo, String reference) {
        validateTenant(tenantId);
        validateSourceId(invoiceId, "invoiceId");
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "A stable payment reference or payment ID is required for idempotent posting");
        }
        UUID legacyPaymentId = UUID.nameUUIDFromBytes((tenantId + "|" + invoiceId + "|" + reference.trim())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        postCustomerPayment(tenantId, legacyPaymentId, invoiceId, amount, currency, invoiceNo, reference);
    }

    @Transactional
    public void postCustomerPayment(UUID tenantId, UUID paymentId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo, String reference) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(paymentId, "paymentId");
        validateSourceId(invoiceId, "invoiceId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "CUSTOMER_PAYMENT", paymentId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "CUSTOMER_PAYMENT", paymentId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c, CASH, "DEBIT", ACCOUNTS_RECEIVABLE, "CREDIT"))
                return;
            throw new IllegalStateException(
                    "Payment ID already has a different accounting posting; do not post it twice");
        }
        String description = "Customer payment " + invoiceNo;
        if (reference != null && !reference.isBlank())
            description += " / " + reference.trim();
        postBalanced(tenantId, invoiceId, "CUSTOMER_PAYMENT", paymentId, amount, c,
                CASH, ACCOUNTS_RECEIVABLE, description);
    }

    @Transactional
    public void postCreditNote(UUID tenantId, UUID noteId, BigDecimal amount,
            String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(noteId, "noteId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "CREDIT_NOTE", noteId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "CREDIT_NOTE", noteId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c, FREIGHT_REVENUE, "DEBIT", ACCOUNTS_RECEIVABLE, "CREDIT"))
                return;
            throw new IllegalStateException("Credit note already has a different accounting posting");
        }
        postBalanced(tenantId, null, "CREDIT_NOTE", noteId, amount, c,
                FREIGHT_REVENUE, ACCOUNTS_RECEIVABLE,
                "Credit note for " + invoiceNo + " / " + reason);
    }

    @Transactional
    public void postDebitNote(UUID tenantId, UUID noteId, BigDecimal amount,
            String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(noteId, "noteId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "DEBIT_NOTE", noteId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "DEBIT_NOTE", noteId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c, ACCOUNTS_RECEIVABLE, "DEBIT", FREIGHT_REVENUE, "CREDIT"))
                return;
            throw new IllegalStateException("Debit note already has a different accounting posting");
        }
        postBalanced(tenantId, null, "DEBIT_NOTE", noteId, amount, c,
                ACCOUNTS_RECEIVABLE, FREIGHT_REVENUE,
                "Debit note for " + invoiceNo + " / " + reason);
    }

    /** Post a gross credit-note adjustment with its tax component separated. */
    @Transactional
    public void postCreditNoteWithTax(UUID tenantId, UUID noteId, BigDecimal grossAmount,
            BigDecimal taxAmount, String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(grossAmount);
        validateTaxComponent(grossAmount, taxAmount);
        validateSourceId(noteId, "noteId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "CREDIT_NOTE", noteId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "CREDIT_NOTE", noteId);
        if (!existing.isEmpty()) {
            if (isExactTaxNote(existing, grossAmount, taxAmount, c, true))
                return;
            throw new IllegalStateException("Credit note already has a different accounting posting");
        }
        BigDecimal revenue = grossAmount.subtract(taxAmount);
        if (revenue.signum() > 0)
            ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                    "DEBIT", FREIGHT_REVENUE, revenue, c,
                    "Credit note revenue reversal for " + invoiceNo + " / " + reason));
        if (taxAmount.signum() > 0)
            ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                    "DEBIT", TAX_PAYABLE, taxAmount, c, "Credit note tax reversal for " + invoiceNo + " / " + reason));
        ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                "CREDIT", ACCOUNTS_RECEIVABLE, grossAmount, c,
                "Credit note receivable adjustment for " + invoiceNo + " / " + reason));
    }

    /** Post a gross debit-note adjustment with its tax component separated. */
    @Transactional
    public void postDebitNoteWithTax(UUID tenantId, UUID noteId, BigDecimal grossAmount,
            BigDecimal taxAmount, String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(grossAmount);
        validateTaxComponent(grossAmount, taxAmount);
        validateSourceId(noteId, "noteId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "DEBIT_NOTE", noteId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "DEBIT_NOTE", noteId);
        if (!existing.isEmpty()) {
            if (isExactTaxNote(existing, grossAmount, taxAmount, c, false))
                return;
            throw new IllegalStateException("Debit note already has a different accounting posting");
        }
        BigDecimal revenue = grossAmount.subtract(taxAmount);
        ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                "DEBIT", ACCOUNTS_RECEIVABLE, grossAmount, c,
                "Debit note receivable adjustment for " + invoiceNo + " / " + reason));
        if (revenue.signum() > 0)
            ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                    "CREDIT", FREIGHT_REVENUE, revenue, c,
                    "Debit note revenue adjustment for " + invoiceNo + " / " + reason));
        if (taxAmount.signum() > 0)
            ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                    "CREDIT", TAX_PAYABLE, taxAmount, c,
                    "Debit note tax adjustment for " + invoiceNo + " / " + reason));
    }

    @Transactional
    public void postExpense(UUID tenantId, UUID expenseId, BigDecimal amount,
            String currency, String expenseReference) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(expenseId, "expenseId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "EXPENSE", expenseId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "EXPENSE", expenseId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c, OPERATING_EXPENSE, "DEBIT", CASH, "CREDIT"))
                return;
            throw new IllegalStateException(
                    "Expense ID already has a different accounting posting; use an adjustment or reversal");
        }
        postBalanced(tenantId, null, "EXPENSE", expenseId, amount, c,
                OPERATING_EXPENSE, CASH, "Operating expense " + expenseReference);
    }

    /**
     * Reconciles the cumulative supplier cash paid on a shipment. Only the
     * difference between the requested cumulative amount and already-posted
     * supplier cash is journaled, making repeated updates idempotent.
     */
    @Transactional
    public void postSupplierPayment(UUID tenantId, UUID shipmentId, BigDecimal cumulativeAmount,
            String currency, String shipmentReference) {
        validateTenant(tenantId);
        validateAmount(cumulativeAmount);
        validateSourceId(shipmentId, "shipmentId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "SUPPLIER_PAYMENT", shipmentId, "ALL");
        List<FinanceLedgerEntry> supplierEntries = sourceEntries(tenantId, "SUPPLIER_PAYMENT", shipmentId);
        if (supplierEntries.stream().anyMatch(e -> !c.equalsIgnoreCase(e.getCurrency()))) {
            throw new IllegalStateException(
                    "Supplier payment currency cannot change for a shipment after posting; use an explicit FX/reversal workflow");
        }
        BigDecimal alreadyPosted = ledger.sumBySource(
                tenantId, "SUPPLIER_PAYMENT", shipmentId, "DEBIT", c);
        BigDecimal prior = alreadyPosted == null ? BigDecimal.ZERO : alreadyPosted;
        if (cumulativeAmount.compareTo(prior) < 0) {
            throw new IllegalArgumentException(
                    "Cumulative supplier payment cannot be lower than the amount already posted; use a documented reversal for corrections");
        }
        BigDecimal unposted = cumulativeAmount.subtract(prior);
        if (unposted.signum() == 0)
            return;
        // Never post new legacy cash-as-cost on a shipment using supplier bills.
        if (entityManager != null) {
            Number billCount = (Number) entityManager.createNativeQuery(
                    "select count(*) from supplier_bills where tenant_id=:tenant and shipment_id=:shipment")
                    .setParameter("tenant", tenantId).setParameter("shipment", shipmentId)
                    .getSingleResult();
            if (billCount.longValue() > 0)
                throw new IllegalStateException(
                        "Supplier bills already exist for this shipment: settle supplier invoices instead");
        }
        postBalanced(tenantId, null, "SUPPLIER_PAYMENT", shipmentId, unposted, c,
                OPERATING_EXPENSE, CASH, "Supplier payment " + shipmentReference);
    }

    /** Recognize supplier cost once, independently of settlement/cash. */
    @Transactional
    public void postSupplierBill(UUID tenantId, UUID billId, BigDecimal amount,
            String currency, String vendorReference) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(billId, "billId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "SUPPLIER_BILL", billId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "SUPPLIER_BILL", billId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c,
                    OPERATING_EXPENSE, "DEBIT", ACCOUNTS_PAYABLE, "CREDIT"))
                return;
            throw new IllegalStateException("Supplier bill already has a conflicting ledger posting");
        }
        postBalanced(tenantId, null, "SUPPLIER_BILL", billId, amount, c,
                OPERATING_EXPENSE, ACCOUNTS_PAYABLE, "Supplier invoice " + vendorReference);
    }

    /** Clear a specific approved payable; never expense the same cash again. */
    @Transactional
    public void postSupplierBillPayment(UUID tenantId, UUID paymentId, BigDecimal amount,
            String currency, String paymentReference) {
        validateTenant(tenantId);
        validateAmount(amount);
        validateSourceId(paymentId, "paymentId");
        String c = normalizeCurrency(currency);
        lockPosting(tenantId, "SUPPLIER_BILL_PAYMENT", paymentId, "ALL");
        List<FinanceLedgerEntry> existing = sourceEntries(tenantId, "SUPPLIER_BILL_PAYMENT", paymentId);
        if (!existing.isEmpty()) {
            if (isExactBalancedPosting(existing, amount, c,
                    ACCOUNTS_PAYABLE, "DEBIT", CASH, "CREDIT"))
                return;
            throw new IllegalStateException("Supplier settlement already has a conflicting ledger posting");
        }
        postBalanced(tenantId, null, "SUPPLIER_BILL_PAYMENT", paymentId, amount, c,
                ACCOUNTS_PAYABLE, CASH, "Supplier settlement " + paymentReference);
    }

    private void postBalanced(UUID tenantId, UUID invoiceId, String sourceType, UUID sourceId,
            BigDecimal amount, String currency, String debitAccount,
            String creditAccount, String description) {
        ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, sourceType, sourceId,
                "DEBIT", debitAccount, amount, currency, description));
        ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, sourceType, sourceId,
                "CREDIT", creditAccount, amount, currency, description));
    }

    private void lockPosting(UUID tenantId, String sourceType, UUID sourceId, String currency) {
        if (entityManager == null) {
            // Allows isolated unit tests to exercise pure posting decisions. In Spring
            // runtime,
            // EntityManager is injected and PostgreSQL serializes concurrent attempts.
            return;
        }
        String key = tenantId + "|" + sourceType + "|" + sourceId + "|" + currency;
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", key)
                .getSingleResult();
    }

    private List<FinanceLedgerEntry> sourceEntries(UUID tenantId, String sourceType, UUID sourceId) {
        return ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenantId, sourceType, sourceId);
    }

    private static boolean isExactSimpleInvoice(List<FinanceLedgerEntry> entries, BigDecimal amount, String currency) {
        if (entries.size() != 2)
            return false;
        return hasLine(entries, ACCOUNTS_RECEIVABLE, "DEBIT", amount, currency)
                && hasLine(entries, FREIGHT_REVENUE, "CREDIT", amount, currency);
    }

    private static boolean isExactTaxInvoice(List<FinanceLedgerEntry> entries, BigDecimal gross, BigDecimal tax,
            String currency) {
        BigDecimal revenue = gross.subtract(tax);
        int expectedLines = 1 + (revenue.signum() > 0 ? 1 : 0) + (tax.signum() > 0 ? 1 : 0);
        if (entries.size() != expectedLines || !hasLine(entries, ACCOUNTS_RECEIVABLE, "DEBIT", gross, currency))
            return false;
        return (revenue.signum() == 0 || hasLine(entries, FREIGHT_REVENUE, "CREDIT", revenue, currency))
                && (tax.signum() == 0 || hasLine(entries, TAX_PAYABLE, "CREDIT", tax, currency));
    }

    private static boolean isExactTaxNote(List<FinanceLedgerEntry> entries, BigDecimal gross, BigDecimal tax,
            String currency, boolean creditNote) {
        BigDecimal revenue = gross.subtract(tax);
        String revenueType = creditNote ? "DEBIT" : "CREDIT";
        String taxType = creditNote ? "DEBIT" : "CREDIT";
        String arType = creditNote ? "CREDIT" : "DEBIT";
        int expectedLines = 1 + (revenue.signum() > 0 ? 1 : 0) + (tax.signum() > 0 ? 1 : 0);
        String arAccount = ACCOUNTS_RECEIVABLE;
        return entries.size() == expectedLines
                && hasLine(entries, arAccount, arType, gross, currency)
                && (revenue.signum() == 0 || hasLine(entries, FREIGHT_REVENUE, revenueType, revenue, currency))
                && (tax.signum() == 0 || hasLine(entries, TAX_PAYABLE, taxType, tax, currency));
    }

    private static boolean isExactBalancedPosting(List<FinanceLedgerEntry> entries, BigDecimal amount, String currency,
            String debitAccount, String debitType, String creditAccount, String creditType) {
        return entries.size() == 2
                && hasLine(entries, debitAccount, debitType, amount, currency)
                && hasLine(entries, creditAccount, creditType, amount, currency);
    }

    private static boolean hasLine(List<FinanceLedgerEntry> entries, String account, String type,
            BigDecimal amount, String currency) {
        return entries.stream().anyMatch(e -> account.equals(e.getAccountCode())
                && type.equalsIgnoreCase(e.getEntryType())
                && amount.compareTo(e.getAmount()) == 0
                && currency.equalsIgnoreCase(e.getCurrency()));
    }

    private static void validateSourceId(UUID id, String field) {
        if (id == null)
            throw new IllegalArgumentException(field + " is required for auditable, idempotent financial posting");
    }

    private static void validateTaxComponent(BigDecimal grossAmount, BigDecimal taxAmount) {
        if (taxAmount == null || taxAmount.signum() < 0 || taxAmount.compareTo(grossAmount) > 0) {
            throw new IllegalArgumentException("Note tax must be between zero and the gross note amount");
        }
    }

    private static void validateTenant(UUID tenantId) {
        if (tenantId == null)
            throw new IllegalArgumentException("Tenant is required for financial posting");
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Financial amount must be greater than zero");
        }
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required for financial posting");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
