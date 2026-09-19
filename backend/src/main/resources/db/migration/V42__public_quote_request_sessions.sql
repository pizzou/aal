CREATE TABLE IF NOT EXISTS public_quote_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    quote_id UUID NOT NULL REFERENCES commercial_quotes(id) ON DELETE CASCADE,
    origin VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    requested_mode VARCHAR(40) NOT NULL,
    commodity VARCHAR(255),
    chargeable_weight_kg NUMERIC(18,3) NOT NULL DEFAULT 0,
    volume_cbm NUMERIC(18,3),
    packages INTEGER,
    company VARCHAR(255),
    contact_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(80),
    notes TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    booked_shipment_id UUID REFERENCES shipments(id),
    booked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_public_quote_requests_token
    ON public_quote_requests(token_hash, expires_at);

CREATE INDEX IF NOT EXISTS idx_public_quote_requests_tenant_created
    ON public_quote_requests(tenant_id, created_at DESC);
