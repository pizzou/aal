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
    void postingWithoutStableSourceIdentityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.postExpense(tenant, null, new BigDecimal("10.00"), "USD", "EXP-2"));
        verifyNoInteractions(ledger);
    }
}
