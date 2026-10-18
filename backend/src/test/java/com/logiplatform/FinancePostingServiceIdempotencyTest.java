package com.logiplatform;

import com.logiplatform.model.FinanceLedgerEntry;
import com.logiplatform.repository.FinanceLedgerRepository;
import com.logiplatform.service.FinancePostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancePostingServiceIdempotencyTest {
    @Mock FinanceLedgerRepository ledger;
    private FinancePostingService service;
    private UUID tenant;
    private UUID expense;

    @BeforeEach
    void setUp() {
        service = new FinancePostingService(ledger);
        tenant = UUID.randomUUID();
        expense = UUID.randomUUID();
    }

    @Test
    void firstExpensePostingCreatesBalancedDebitAndCredit() {
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "EXPENSE", expense))
                .thenReturn(List.of());

        service.postExpense(tenant, expense, new BigDecimal("125.00"), "USD", "EXP-1");

        verify(ledger, times(2)).save(any(FinanceLedgerEntry.class));
    }

    @Test
    void retryWithSameExpenseIdentityAndAmountDoesNotPostAgain() {
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "EXPENSE", expense))
                .thenReturn(List.of(
                        new FinanceLedgerEntry(tenant, null, "EXPENSE", expense, "DEBIT",
                                FinancePostingService.OPERATING_EXPENSE, new BigDecimal("125.00"), "USD", "Expense"),
                        new FinanceLedgerEntry(tenant, null, "EXPENSE", expense, "CREDIT",
                                FinancePostingService.CASH, new BigDecimal("125.00"), "USD", "Expense")));

        service.postExpense(tenant, expense, new BigDecimal("125.00"), "USD", "EXP-1");

        verify(ledger, never()).save(any(FinanceLedgerEntry.class));
    }

    @Test
    void reusingExpenseIdentityWithDifferentAmountIsRejected() {
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "EXPENSE", expense))
                .thenReturn(List.of(
                        new FinanceLedgerEntry(tenant, null, "EXPENSE", expense, "DEBIT",
                                FinancePostingService.OPERATING_EXPENSE, new BigDecimal("125.00"), "USD", "Expense"),
                        new FinanceLedgerEntry(tenant, null, "EXPENSE", expense, "CREDIT",
                                FinancePostingService.CASH, new BigDecimal("125.00"), "USD", "Expense")));

        assertThrows(IllegalStateException.class,
                () -> service.postExpense(tenant, expense, new BigDecimal("150.00"), "USD", "EXP-1"));
        verify(ledger, never()).save(any(FinanceLedgerEntry.class));
    }


    @Test
    void taxInvoicePostsReceivableRevenueAndTaxAsBalancedLines() {
        UUID invoice = UUID.randomUUID();
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "INVOICE", invoice))
                .thenReturn(List.of());
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "INVOICE_REVERSAL", invoice))
                .thenReturn(List.of());

        service.postInvoiceWithTax(tenant, invoice, new BigDecimal("118.00"),
                new BigDecimal("18.00"), "usd", "INV-TAX-1");

        verify(ledger, times(3)).save(any(FinanceLedgerEntry.class));
    }

    @Test
    void taxInvoiceRetryWithSameAmountsDoesNotDuplicateJournalLines() {
        UUID invoice = UUID.randomUUID();
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "INVOICE", invoice))
                .thenReturn(List.of(
                        new FinanceLedgerEntry(tenant, invoice, "INVOICE", invoice, "DEBIT",
                                FinancePostingService.ACCOUNTS_RECEIVABLE, new BigDecimal("118.00"), "USD", "Invoice"),
                        new FinanceLedgerEntry(tenant, invoice, "INVOICE", invoice, "CREDIT",
                                FinancePostingService.FREIGHT_REVENUE, new BigDecimal("100.00"), "USD", "Revenue"),
                        new FinanceLedgerEntry(tenant, invoice, "INVOICE", invoice, "CREDIT",
                                FinancePostingService.TAX_PAYABLE, new BigDecimal("18.00"), "USD", "Tax")));
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "INVOICE_REVERSAL", invoice))
                .thenReturn(List.of());

        service.postInvoiceWithTax(tenant, invoice, new BigDecimal("118.00"),
                new BigDecimal("18.00"), "USD", "INV-TAX-1");

        verify(ledger, never()).save(any(FinanceLedgerEntry.class));
    }

    @Test
    void taxGreaterThanGrossIsRejectedBeforePosting() {
        assertThrows(IllegalArgumentException.class,
                () -> service.postInvoiceWithTax(tenant, UUID.randomUUID(),
                        new BigDecimal("100.00"), new BigDecimal("101.00"), "USD", "INV-TAX-2"));
        verifyNoInteractions(ledger);
    }

    @Test
    void postingWithoutStableSourceIdentityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.postExpense(tenant, null, new BigDecimal("10.00"), "USD", "EXP-2"));
        verifyNoInteractions(ledger);
    }
}
