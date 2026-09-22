package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class FinanceDocumentSequenceService {
    private final JdbcTemplate db;

    public FinanceDocumentSequenceService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public String nextInvoiceNumber() {
        UUID tenant = TenantContext.getTenantId();
        int year = LocalDate.now().getYear();
        db.update("""
            INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
            VALUES(?,?,?,0)
            ON CONFLICT(tenant_id,document_type,fiscal_year) DO NOTHING
            """, tenant, "INVOICE", year);
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='INVOICE' AND fiscal_year=?
            RETURNING last_value
            """, Long.class, tenant, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate invoice sequence");
        return "AAL-INV-" + year + "-" + String.format("%06d", sequence);
    }
}
