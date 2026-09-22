ALTER TABLE cargo_documents
  ADD COLUMN IF NOT EXISTS scanned_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS scanner_version VARCHAR(120);

ALTER TABLE document_signature_requests
  ADD COLUMN IF NOT EXISTS provider VARCHAR(60) NOT NULL DEFAULT 'INTERNAL_AUDIT',
  ADD COLUMN IF NOT EXISTS signature_hash VARCHAR(128);

CREATE INDEX IF NOT EXISTS ix_document_signature_requests_status
  ON document_signature_requests(tenant_id,document_id,status,requested_at DESC);

ALTER TABLE document_signature_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_signature_requests FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_document_signature_requests ON document_signature_requests;
CREATE POLICY tenant_isolation_document_signature_requests
  ON document_signature_requests
  USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
