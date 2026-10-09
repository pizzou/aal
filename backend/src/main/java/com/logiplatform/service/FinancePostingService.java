package com.logiplatform.service;

import com.logiplatform.model.FinanceLedgerEntry;
import com.logiplatform.repository.FinanceLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final FinanceLedgerRepository ledger;

    public FinancePostingService(FinanceLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public void postInvoice(UUID tenantId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo) {
        validateTenant(tenantId);
        validateAmount(amount);
        String c = normalizeCurrency(currency);
        BigDecimal alreadyPosted = ledger.sumBySource(
                tenantId, "INVOICE", invoiceId, "DEBIT", c);
        BigDecimal unposted = amount.subtract(alreadyPosted == null ? BigDecimal.ZERO : alreadyPosted);
        if (unposted.signum() <= 0) return;
        postBalanced(tenantId, invoiceId, "INVOICE", invoiceId, unposted, c,
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
        String c = normalizeCurrency(currency);
        BigDecimal alreadyPosted = ledger.sumBySource(tenantId, "INVOICE", invoiceId, "DEBIT", c);
        if (alreadyPosted != null && alreadyPosted.signum() > 0) {
            throw new IllegalStateException("Invoice already has an accounting posting; do not re-post tax");
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
        // The source may be in any currency; check all immutable reversal rows.
        List<FinanceLedgerEntry> priorReversal = ledger
                .findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenantId, "INVOICE_REVERSAL", invoiceId);
        if (!priorReversal.isEmpty()) return;
        List<FinanceLedgerEntry> originals = ledger
                .findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenantId, "INVOICE", invoiceId);
        if (originals.isEmpty()) return; // A draft has no journal to reverse.
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
        postCustomerPayment(tenantId, null, invoiceId, amount, currency, invoiceNo, reference);
    }

    @Transactional
    public void postCustomerPayment(UUID tenantId, UUID paymentId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo, String reference) {
        validateTenant(tenantId);
        validateAmount(amount);
        String c = normalizeCurrency(currency);
        if (paymentId != null) {
            BigDecimal alreadyPosted = ledger.sumBySource(
                    tenantId, "CUSTOMER_PAYMENT", paymentId, "DEBIT", c);
            BigDecimal unposted = amount.subtract(alreadyPosted == null ? BigDecimal.ZERO : alreadyPosted);
            if (unposted.signum() <= 0) return;
            amount = unposted;
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
        String c = normalizeCurrency(currency);
        postBalanced(tenantId, null, "CREDIT_NOTE", noteId, amount, c,
                FREIGHT_REVENUE, ACCOUNTS_RECEIVABLE,
                "Credit note for " + invoiceNo + " / " + reason);
    }

    @Transactional
    public void postDebitNote(UUID tenantId, UUID noteId, BigDecimal amount,
            String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(amount);
        String c = normalizeCurrency(currency);
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
        String c = normalizeCurrency(currency);
        BigDecimal revenue = grossAmount.subtract(taxAmount);
        if (revenue.signum() > 0) ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                "DEBIT", FREIGHT_REVENUE, revenue, c, "Credit note revenue reversal for " + invoiceNo + " / " + reason));
        if (taxAmount.signum() > 0) ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                "DEBIT", TAX_PAYABLE, taxAmount, c, "Credit note tax reversal for " + invoiceNo + " / " + reason));
        ledger.save(new FinanceLedgerEntry(tenantId, null, "CREDIT_NOTE", noteId,
                "CREDIT", ACCOUNTS_RECEIVABLE, grossAmount, c, "Credit note receivable adjustment for " + invoiceNo + " / " + reason));
    }

    /** Post a gross debit-note adjustment with its tax component separated. */
    @Transactional
    public void postDebitNoteWithTax(UUID tenantId, UUID noteId, BigDecimal grossAmount,
            BigDecimal taxAmount, String currency, String invoiceNo, String reason) {
        validateTenant(tenantId);
        validateAmount(grossAmount);
        validateTaxComponent(grossAmount, taxAmount);
        String c = normalizeCurrency(currency);
        BigDecimal revenue = grossAmount.subtract(taxAmount);
        ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                "DEBIT", ACCOUNTS_RECEIVABLE, grossAmount, c, "Debit note receivable adjustment for " + invoiceNo + " / " + reason));
        if (revenue.signum() > 0) ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                "CREDIT", FREIGHT_REVENUE, revenue, c, "Debit note revenue adjustment for " + invoiceNo + " / " + reason));
        if (taxAmount.signum() > 0) ledger.save(new FinanceLedgerEntry(tenantId, null, "DEBIT_NOTE", noteId,
                "CREDIT", TAX_PAYABLE, taxAmount, c, "Debit note tax adjustment for " + invoiceNo + " / " + reason));
    }

    @Transactional
    public void postExpense(UUID tenantId, UUID expenseId, BigDecimal amount,
            String currency, String expenseReference) {
        validateTenant(tenantId);
        validateAmount(amount);
        String c = normalizeCurrency(currency);
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
        String c = normalizeCurrency(currency);
        BigDecimal alreadyPosted = ledger.sumBySource(
                tenantId, "SUPPLIER_PAYMENT", shipmentId, "DEBIT", c);
        BigDecimal unposted = cumulativeAmount.subtract(alreadyPosted == null ? BigDecimal.ZERO : alreadyPosted);
        if (unposted.signum() <= 0)
            return;
        postBalanced(tenantId, null, "SUPPLIER_PAYMENT", shipmentId, unposted, c,
                OPERATING_EXPENSE, CASH, "Supplier payment " + shipmentReference);
    }

    private void postBalanced(UUID tenantId, UUID invoiceId, String sourceType, UUID sourceId,
            BigDecimal amount, String currency, String debitAccount,
            String creditAccount, String description) {
        ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, sourceType, sourceId,
                "DEBIT", debitAccount, amount, currency, description));
        ledger.save(new FinanceLedgerEntry(tenantId, invoiceId, sourceType, sourceId,
                "CREDIT", creditAccount, amount, currency, description));
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
