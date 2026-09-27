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
    public String nextShipmentNumber() {
        UUID tenant = requireTenant();
        int year = LocalDate.now().getYear();
        db.update("""
            INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
            VALUES(?,?,?,0)
            ON CONFLICT(tenant_id,document_type,fiscal_year) DO NOTHING
            """, tenant, "SHIPMENT", year);
        synchronizeSequenceWithExistingData(
            tenant,
            "SHIPMENT",
            year,
            "shipments",
            "reference_code",
            "AAL-SHP-"
        );
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='SHIPMENT' AND fiscal_year=?
            RETURNING last_value
            """, Long.class, tenant, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate shipment sequence");
        return "AAL-SHP-" + year + "-" + String.format("%06d", sequence);
    }

    @Transactional
    public String nextAwbNumber() {
        UUID tenant = requireTenant();
        int year = LocalDate.now().getYear();
        db.update("""
            INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
            VALUES(?,?,?,0)
            ON CONFLICT(tenant_id,document_type,fiscal_year) DO NOTHING
            """, tenant, "AWB", year);
        synchronizeSequenceWithExistingData(
            tenant,
            "AWB",
            year,
            "shipments",
            "reference_code",
            "AAL-AWB-"
        );
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='AWB' AND fiscal_year=?
            RETURNING last_value
            """, Long.class, tenant, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate AWB sequence");
        return "AAL-AWB-" + year + "-" + String.format("%06d", sequence);
    }

    @Transactional
    public String nextInvoiceNumber() {
        UUID tenant = requireTenant();
        int year = LocalDate.now().getYear();
        db.update("""
            INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
            VALUES(?,?,?,0)
            ON CONFLICT(tenant_id,document_type,fiscal_year) DO NOTHING
            """, tenant, "INVOICE", year);
        synchronizeSequenceWithExistingData(
            tenant,
            "INVOICE",
            year,
            "commercial_invoices",
            "invoice_no",
            "AAL-INV-"
        );
        synchronizeSequenceWithExistingData(
            tenant,
            "INVOICE",
            year,
            "shipments",
            "invoice_no",
            "AAL-INV-"
        );
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='INVOICE' AND fiscal_year=?
            RETURNING last_value
            """, Long.class, tenant, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate invoice sequence");
        return "AAL-INV-" + year + "-" + String.format("%06d", sequence);
    }


    /**
     * Keeps the durable sequence cursor ahead of legacy/imported documents.
     *
     * The original sequence table is concurrency-safe, but a sequence can be
     * legitimately behind historical Excel-imported numbers. In that case the
     * next generated number would collide with an existing document and the
     * API would return HTTP 409. We advance the cursor to the highest existing
     * suffix before taking the next atomic value.
     */
    private void synchronizeSequenceWithExistingData(
            UUID tenant,
            String documentType,
            int year,
            String table,
            String column,
            String prefix
    ) {
        String sql = """
            SELECT COALESCE(MAX(
                CASE
                    WHEN %s ~ ? THEN
                        substring(%s FROM '([0-9]+)$')::BIGINT
                    ELSE 0
                END
            ), 0)
            FROM %s
            WHERE tenant_id = ?
              AND %s ~ ?
            """.formatted(column, column, table, column);

        String exactPattern = "^" + prefix + year + "-[0-9]+$";
        Long maxExisting = db.queryForObject(
                sql,
                Long.class,
                exactPattern,
                tenant,
                exactPattern
        );

        if (maxExisting == null || maxExisting <= 0) {
            return;
        }

        db.update("""
            UPDATE finance_document_sequences
               SET last_value=GREATEST(last_value, ?),updated_at=now()
             WHERE tenant_id=? AND document_type=? AND fiscal_year=?
            """, maxExisting, tenant, documentType, year);
    }

    private UUID requireTenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new IllegalStateException("Tenant context is required for document number allocation");
        }
        return tenant;
    }
}
