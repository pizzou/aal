-- Enterprise integration auditability and deployment-readiness evidence.
CREATE TABLE IF NOT EXISTS integration_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  provider VARCHAR(80) NOT NULL,
  operation VARCHAR(120) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(160),
  correlation_id VARCHAR(160),
  http_status INTEGER,
  status VARCHAR(32) NOT NULL,
  error_detail TEXT,
  request_hash VARCHAR(128),
  response_hash VARCHAR(128),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_integration_attempts_tenant_time
  ON integration_attempts(tenant_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_integration_attempts_tenant_key
  ON integration_attempts(tenant_id, provider, operation, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS edi_messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  standard VARCHAR(40) NOT NULL,
  message_type VARCHAR(40) NOT NULL,
  control_reference VARCHAR(80),
  direction VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
  payload TEXT NOT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  parsed_json TEXT,
  error_detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_edi_messages_tenant_time
  ON edi_messages(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS webhook_delivery_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  event_type VARCHAR(120) NOT NULL,
  target_url TEXT NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  attempt_no INTEGER NOT NULL,
  http_status INTEGER,
  status VARCHAR(32) NOT NULL,
  response_excerpt TEXT,
  error_detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_webhook_delivery_tenant_time
  ON webhook_delivery_attempts(tenant_id, created_at DESC);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['integration_attempts','edi_messages','webhook_delivery_attempts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I', t, t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',
      t, t
    );
  END LOOP;
END $$;
