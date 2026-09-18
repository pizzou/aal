package com.logiplatform.service;

import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.FinanceLedgerRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import static com.logiplatform.dto.FinanceReconciliationDtos.Response;

@Service
public class FinanceReconciliationService {
    private final CommercialInvoiceRepository invoices;
    private final FinanceLedgerRepository ledger;
    public FinanceReconciliationService(CommercialInvoiceRepository invoices, FinanceLedgerRepository ledger){this.invoices=invoices;this.ledger=ledger;}

    @Transactional(readOnly=true)
    public Response reconcile(LocalDate asOf, String currency) {
        var tenant=TenantContext.getTenantId();
        if(tenant==null) throw new IllegalStateException("Tenant context is required");
        LocalDate date=asOf==null?LocalDate.now():asOf;
        String c=currency==null||currency.isBlank()?"USD":currency.trim().toUpperCase(Locale.ROOT);
        BigDecimal invoiceReceivable=invoices.findAllByTenantIdOrderByIssueDateDesc(tenant).stream()
                .filter(i->!i.getIssueDate().isAfter(date)&&c.equalsIgnoreCase(i.getCurrency()))
                .map(i->i.getBalance().max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal arDebit=nz(ledger.sumByAccount(tenant,FinancePostingService.ACCOUNTS_RECEIVABLE,"DEBIT",c));
        BigDecimal arCredit=nz(ledger.sumByAccount(tenant,FinancePostingService.ACCOUNTS_RECEIVABLE,"CREDIT",c));
        BigDecimal ledgerReceivable=arDebit.subtract(arCredit);
        BigDecimal cashDebit=nz(ledger.sumByAccount(tenant,FinancePostingService.CASH,"DEBIT",c));
        BigDecimal cashCredit=nz(ledger.sumByAccount(tenant,FinancePostingService.CASH,"CREDIT",c));
        // Supplier events are source-linked to shipments; aggregate their tenant ledger entries.
        BigDecimal supplierPosted=ledger.findAllByTenantIdOrderByPostedAtDesc(tenant).stream().filter(e->"SUPPLIER_PAYMENT".equals(e.getSourceType())&&"DEBIT".equals(e.getEntryType())&&c.equalsIgnoreCase(e.getCurrency())).map(e->e.getAmount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal expenses=ledger.findAllByTenantIdOrderByPostedAtDesc(tenant).stream().filter(e->"EXPENSE".equals(e.getSourceType())&&"DEBIT".equals(e.getEntryType())&&c.equalsIgnoreCase(e.getCurrency())).map(e->e.getAmount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal difference=invoiceReceivable.subtract(ledgerReceivable);
        return new Response(date,c,invoiceReceivable,ledgerReceivable,difference,cashDebit.subtract(cashCredit),supplierPosted,expenses,difference.signum()==0?"PASS":"FAIL");
    }
    private static BigDecimal nz(BigDecimal x){return x==null?BigDecimal.ZERO:x;}
}
