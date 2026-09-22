-- Concurrency-safe invoice numbering by tenant/year.
CREATE TABLE IF NOT EXISTS finance_document_sequences (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  document_type VARCHAR(40) NOT NULL,
  fiscal_year INTEGER NOT NULL,
  last_value BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,document_type,fiscal_year)
);
CREATE INDEX IF NOT EXISTS ix_finance_document_sequences_lookup
  ON finance_document_sequences(tenant_id,document_type,fiscal_year);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['finance_document_sequences'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
