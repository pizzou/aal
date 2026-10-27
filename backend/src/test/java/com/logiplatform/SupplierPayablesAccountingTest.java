package com.logiplatform;

import com.logiplatform.model.FinanceLedgerEntry;
import com.logiplatform.repository.FinanceLedgerRepository;
import com.logiplatform.service.FinancePostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierPayablesAccountingTest {
    private FinanceLedgerRepository ledger;
    private FinancePostingService finance;
    private UUID tenant;
    private UUID source;

    @BeforeEach void setup() {
        ledger = mock(FinanceLedgerRepository.class);
        finance = new FinancePostingService(ledger);
        tenant = UUID.randomUUID();
        source = UUID.randomUUID();
    }

    @Test void supplierInvoiceAccruesExpenseAgainstPayables() {
        finance.postSupplierBill(tenant, source, new BigDecimal("250.00"), "usd", "SUP-1");
        ArgumentCaptor<FinanceLedgerEntry> capture = ArgumentCaptor.forClass(FinanceLedgerEntry.class);
        verify(ledger, times(2)).save(capture.capture());
        List<FinanceLedgerEntry> entries = capture.getAllValues();
        assertEquals("5100-OPERATING_EXPENSE", entries.get(0).getAccountCode());
        assertEquals("DEBIT", entries.get(0).getEntryType());
        assertEquals("2100-ACCOUNTS_PAYABLE", entries.get(1).getAccountCode());
        assertEquals("CREDIT", entries.get(1).getEntryType());
        assertEquals(entries.get(0).getAmount(), entries.get(1).getAmount());
    }

    @Test void supplierSettlementClearsPayableWithoutRecordingAnotherExpense() {
        finance.postSupplierBillPayment(tenant, source, new BigDecimal("50.00"), "USD", "BANK-3");
        ArgumentCaptor<FinanceLedgerEntry> capture = ArgumentCaptor.forClass(FinanceLedgerEntry.class);
        verify(ledger, times(2)).save(capture.capture());
        List<FinanceLedgerEntry> entries = capture.getAllValues();
        assertEquals("2100-ACCOUNTS_PAYABLE", entries.get(0).getAccountCode());
        assertEquals("DEBIT", entries.get(0).getEntryType());
        assertEquals("1000-CASH", entries.get(1).getAccountCode());
        assertEquals("CREDIT", entries.get(1).getEntryType());
    }

    @Test void idempotentBillRetryDoesNotBookSecondCost() {
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "SUPPLIER_BILL", source))
            .thenReturn(List.of(
                new FinanceLedgerEntry(tenant, null, "SUPPLIER_BILL", source, "DEBIT", "5100-OPERATING_EXPENSE", new BigDecimal("250.00"), "USD", "bill"),
                new FinanceLedgerEntry(tenant, null, "SUPPLIER_BILL", source, "CREDIT", "2100-ACCOUNTS_PAYABLE", new BigDecimal("250.00"), "USD", "bill")));
        finance.postSupplierBill(tenant, source, new BigDecimal("250.00"), "USD", "SUP-1");
        verify(ledger, never()).save(any());
    }

    @Test void retryWithDifferentAmountCannotOverwriteRecordedSupplierCost() {
        when(ledger.findAllByTenantIdAndSourceTypeAndSourceIdOrderByPostedAtAsc(tenant, "SUPPLIER_BILL", source))
            .thenReturn(List.of(
                new FinanceLedgerEntry(tenant, null, "SUPPLIER_BILL", source, "DEBIT", "5100-OPERATING_EXPENSE", new BigDecimal("250.00"), "USD", "bill"),
                new FinanceLedgerEntry(tenant, null, "SUPPLIER_BILL", source, "CREDIT", "2100-ACCOUNTS_PAYABLE", new BigDecimal("250.00"), "USD", "bill")));
        assertThrows(IllegalStateException.class, () ->
                finance.postSupplierBill(tenant, source, new BigDecimal("275.00"), "USD", "SUP-1"));
        verify(ledger, never()).save(any());
    }
}
