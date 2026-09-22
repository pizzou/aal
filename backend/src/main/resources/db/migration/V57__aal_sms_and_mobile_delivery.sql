-- SMS delivery attempts and mobile synchronization metadata.
CREATE TABLE IF NOT EXISTS sms_delivery_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID REFERENCES shipments(id),
  recipient VARCHAR(80) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  message_body TEXT NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  provider_reference VARCHAR(255),
  error_detail TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at TIMESTAMPTZ,
  UNIQUE(tenant_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS ix_sms_delivery_queue
  ON sms_delivery_attempts(tenant_id,status,created_at);

ALTER TABLE mobile_sync_queue
  ADD COLUMN IF NOT EXISTS device_timestamp TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_error TEXT,
  ADD COLUMN IF NOT EXISTS checksum_sha256 VARCHAR(128);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['sms_delivery_attempts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
