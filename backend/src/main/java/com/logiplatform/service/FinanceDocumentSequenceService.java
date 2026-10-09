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
        ensureSequenceRow(tenant, "SHIPMENT", year);
        lockSequenceRow(tenant, "SHIPMENT", year);
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
        ensureSequenceRow(tenant, "AWB", year);
        lockSequenceRow(tenant, "AWB", year);
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
        String prefix = db.query("""
                SELECT invoice_prefix FROM finance_tax_jurisdictions
                 WHERE tenant_id=? AND active=true AND invoice_prefix IS NOT NULL AND invoice_prefix <> ''
                 ORDER BY updated_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : "AAL-INV-", tenant);
        if (prefix == null || prefix.isBlank()) prefix = "AAL-INV-";
        prefix = prefix.trim().toUpperCase(java.util.Locale.ROOT);
        if (!prefix.endsWith("-")) prefix += "-";

        ensureSequenceRow(tenant, "INVOICE", year);
        lockSequenceRow(tenant, "INVOICE", year);
        synchronizeSequenceWithExistingData(
            tenant, "INVOICE", year, "commercial_invoices", "invoice_no", prefix);
        synchronizeSequenceWithExistingData(
            tenant, "INVOICE", year, "shipments", "invoice_no", prefix);
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='INVOICE' AND fiscal_year=?
            RETURNING last_value
            """, Long.class, tenant, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate invoice sequence");
        return prefix + year + "-" + String.format("%06d", sequence);
    }


    @Transactional
    public String nextFinanceNoteNumber(String noteType) {
        UUID tenant = requireTenant();
        String type = noteType == null ? "" : noteType.trim().toUpperCase(java.util.Locale.ROOT);
        String prefix = switch (type) {
            case "CREDIT" -> "AAL-CN-";
            case "DEBIT" -> "AAL-DN-";
            default -> throw new IllegalArgumentException("Finance note type must be CREDIT or DEBIT");
        };
        int year = LocalDate.now().getYear();
        String documentType = "CREDIT".equals(type) ? "CREDIT_NOTE" : "DEBIT_NOTE";
        ensureSequenceRow(tenant, documentType, year);
        lockSequenceRow(tenant, documentType, year);
        synchronizeSequenceWithExistingData(tenant, documentType, year,
                "finance_notes", "note_no", prefix);
        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type=? AND fiscal_year=?
             RETURNING last_value
            """, Long.class, tenant, documentType, year);
        if (sequence == null) throw new IllegalStateException("Unable to allocate finance note number");
        return prefix + year + "-" + String.format("%06d", sequence);
    }

    @Transactional
    public String nextReceiptNumber() {
        UUID tenant = requireTenant();
        int year = LocalDate.now().getYear();
        ensureSequenceRow(tenant, "RECEIPT", year);
        lockSequenceRow(tenant, "RECEIPT", year);
        synchronizeSequenceWithExistingData(
                tenant, "RECEIPT", year,
                "commercial_payments", "receipt_no", "AAL-RCT-");

        Long sequence = db.queryForObject("""
            UPDATE finance_document_sequences
               SET last_value=last_value+1,updated_at=now()
             WHERE tenant_id=? AND document_type='RECEIPT' AND fiscal_year=?
             RETURNING last_value
            """, Long.class, tenant, year);

        if (sequence == null) {
            throw new IllegalStateException("Unable to allocate receipt sequence");
        }
        return "AAL-RCT-" + year + "-" + String.format("%06d", sequence);
    }


    private void ensureSequenceRow(UUID tenant, String documentType, int year) {
        db.update("""
            INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
            VALUES(?,?,?,0)
            ON CONFLICT(tenant_id,document_type,fiscal_year) DO NOTHING
            """, tenant, documentType, year);
    }

    /**
     * Serialize allocation for a tenant/document/year before reconciling the
     * cursor with imported legacy data. The previous implementation performed
     * the reconciliation before taking a row lock, which left a small race
     * window where concurrent shipment creation could allocate the same
     * document number after an import or cursor repair.
     */
    private void lockSequenceRow(UUID tenant, String documentType, int year) {
        db.queryForObject("""
            SELECT id
              FROM finance_document_sequences
             WHERE tenant_id=? AND document_type=? AND fiscal_year=?
             FOR UPDATE
            """, UUID.class, tenant, documentType, year);
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
