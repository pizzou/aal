package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "aal_import_batches", uniqueConstraints = @UniqueConstraint(
        name = "uk_aal_import_batch_hash", columnNames = {"tenant_id", "source_sha256"}))
public class AalImportBatch {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="source_filename", nullable=false) private String sourceFilename;
    @Column(name="source_sha256", nullable=false, length=64) private String sourceSha256;
    @Column(name="status", nullable=false, length=32) private String status;
    @Column(name="started_at", nullable=false) private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="shipments", nullable=false) private int shipments;
    @Column(name="quotations", nullable=false) private int quotations;
    @Column(name="invoices", nullable=false) private int invoices;
    @Column(name="clients", nullable=false) private int clients;
    @Column(name="partners", nullable=false) private int partners;
    @Column(name="tasks", nullable=false) private int tasks;
    @Column(name="expenses", nullable=false) private int expenses;

    protected AalImportBatch() {}

    public AalImportBatch(UUID tenantId, String sourceFilename, String sourceSha256) {
        this.tenantId = tenantId;
        this.sourceFilename = sourceFilename;
        this.sourceSha256 = sourceSha256;
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }

    public void complete(AalImportCounts c) {
        this.status = "COMPLETED";
        this.completedAt = Instant.now();
        this.shipments = c.shipments(); this.quotations = c.quotations(); this.invoices = c.invoices();
        this.clients = c.clients(); this.partners = c.partners(); this.tasks = c.tasks(); this.expenses = c.expenses();
    }
    public void fail() { this.status = "FAILED"; this.completedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getSourceFilename() { return sourceFilename; }
    public String getSourceSha256() { return sourceSha256; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public AalImportCounts counts() { return new AalImportCounts(shipments, quotations, invoices, clients, partners, tasks, expenses); }

    public record AalImportCounts(int shipments, int quotations, int invoices, int clients, int partners, int tasks, int expenses) {}
}
