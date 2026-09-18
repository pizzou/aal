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
        postBalanced(tenantId, invoiceId, "INVOICE", invoiceId, amount, c,
                ACCOUNTS_RECEIVABLE, FREIGHT_REVENUE, "Freight invoice " + invoiceNo);
    }

    @Transactional
    public void postCustomerPayment(UUID tenantId, UUID invoiceId, BigDecimal amount,
            String currency, String invoiceNo, String reference) {
        validateTenant(tenantId);
        validateAmount(amount);
        String c = normalizeCurrency(currency);
        String description = "Customer payment " + invoiceNo;
        if (reference != null && !reference.isBlank())
            description += " / " + reference.trim();
        postBalanced(tenantId, invoiceId, "CUSTOMER_PAYMENT", null, amount, c,
                CASH, ACCOUNTS_RECEIVABLE, description);
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
