-- Idempotent high-frequency GPS ingestion support.
CREATE TABLE IF NOT EXISTS gps_ingestion_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  event_id VARCHAR(160) NOT NULL,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  device_id VARCHAR(128),
  source VARCHAR(32) NOT NULL,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  speed_kmh DOUBLE PRECISION,
  heading_degrees DOUBLE PRECISION,
  accuracy_meters DOUBLE PRECISION,
  battery_percent DOUBLE PRECISION,
  recorded_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  error_detail TEXT,
  UNIQUE(tenant_id,event_id)
);
CREATE INDEX IF NOT EXISTS ix_gps_ingestion_vehicle_time
  ON gps_ingestion_events(tenant_id,vehicle_id,recorded_at DESC);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['gps_ingestion_events'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
