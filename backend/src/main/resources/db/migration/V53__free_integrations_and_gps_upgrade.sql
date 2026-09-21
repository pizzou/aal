-- AAL free/open integration upgrade: richer GPS telemetry + provider/device mapping.
ALTER TABLE vehicle_gps_positions
    ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS device_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS accuracy_meters DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS battery_percent DOUBLE PRECISION;

CREATE INDEX IF NOT EXISTS idx_gps_tenant_vehicle_source_time
    ON vehicle_gps_positions(tenant_id, vehicle_id, source, recorded_at DESC);

CREATE TABLE IF NOT EXISTS vehicle_gps_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    provider VARCHAR(40) NOT NULL,
    external_device_id VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, vehicle_id, provider),
    UNIQUE (tenant_id, provider, external_device_id)
);

ALTER TABLE vehicle_gps_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_gps_devices FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_vehicle_gps_devices ON vehicle_gps_devices
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE INDEX IF NOT EXISTS idx_vehicle_gps_devices_tenant_enabled
    ON vehicle_gps_devices(tenant_id, enabled);

INSERT INTO integration_registry (tenant_id, code, display_name, protocol, base_url, enabled)
SELECT t.id,
       x.code, x.display_name, x.protocol, x.base_url, FALSE
FROM tenants t
CROSS JOIN (VALUES
    ('TRACCAR', 'Traccar GPS', 'REST', 'http://localhost:8082/api'),
    ('AMADEUS', 'Amadeus Flights', 'REST', 'https://test.api.amadeus.com'),
    ('PAYPAL_SANDBOX', 'PayPal Sandbox', 'REST', 'https://api-m.sandbox.paypal.com'),
    ('DCSA_SANDBOX', 'DCSA Sandbox', 'REST', ''),
    ('ERPNEXT', 'ERPNext / Frappe', 'REST', ''),
    ('WHATSAPP_CLOUD', 'WhatsApp Cloud API', 'REST', 'https://graph.facebook.com'),
    ('VALHALLA', 'Valhalla Routing', 'REST', 'http://localhost:8002'),
    ('OPEN_METEO', 'Open-Meteo Weather', 'REST', 'https://api.open-meteo.com')
) AS x(code, display_name, protocol, base_url)
ON CONFLICT (tenant_id, code) DO NOTHING;
