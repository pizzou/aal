package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class FinanceReconciliationDtos {
    private FinanceReconciliationDtos() {}
    public record Response(LocalDate asOf, String currency, BigDecimal invoiceReceivable,
                           BigDecimal ledgerReceivable, BigDecimal receivableDifference,
                           BigDecimal ledgerCash, BigDecimal supplierPaymentsPosted,
                           BigDecimal operatingExpensesPosted, String status) {}
}
