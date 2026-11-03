-- Keep signed POD evidence inside the tenant-isolated application, not in
-- unverified public links supplied by staff. References are generated on server.
CREATE TABLE pod_evidence_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    filename VARCHAR(180) NOT NULL,
    mime_type VARCHAR(60) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 8388608),
    checksum_sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    scanner_status VARCHAR(20) NOT NULL DEFAULT 'CLEAN' CHECK (scanner_status = 'CLEAN'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, shipment_id, checksum_sha256)
);
CREATE INDEX ix_pod_evidence_shipment ON pod_evidence_files (tenant_id, shipment_id, created_at DESC);
ALTER TABLE pod_evidence_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE pod_evidence_files FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_pod_evidence_files ON pod_evidence_files
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- Immutable after clean upload, including for privileged operational roles.
CREATE TRIGGER trg_pod_evidence_files_append_only
    BEFORE UPDATE OR DELETE ON pod_evidence_files
    FOR EACH ROW EXECUTE FUNCTION aal_reject_logistics_evidence_mutation();
