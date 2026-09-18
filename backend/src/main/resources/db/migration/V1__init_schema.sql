
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- email is GLOBALLY unique (not per-tenant): a user account belongs to exactly one
-- tenant, and login looks a user up by email alone before any tenant is known.
-- This is what makes the pre-tenant login lookup in AuthDataConfig/AuthService safe
-- and simple — see the comment there for why that lookup exists outside RLS.
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'ADMIN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL REFERENCES tenants(id),

    reference_code VARCHAR(100) NOT NULL,
    origin_address TEXT NOT NULL,
    destination_address TEXT NOT NULL,

    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    transport_mode VARCHAR(20) NOT NULL DEFAULT 'ROAD',

    carrier_name VARCHAR(255),
    carrier_reference_number VARCHAR(100),

    tracking_token UUID NOT NULL UNIQUE,

    weight_kg INT,
    notification_email VARCHAR(255),
    flight_number VARCHAR(20),

    client_name VARCHAR(255),
    contact VARCHAR(255),
    commodity TEXT,

    origin_country VARCHAR(120),
    origin_city_port VARCHAR(255),
    destination_country VARCHAR(120),
    destination_city_port VARCHAR(255),

    gross_weight_kg NUMERIC(18,3),
    volumetric_weight_kg NUMERIC(18,3),
    chargeable_weight_kg NUMERIC(18,3),

    packages INTEGER,
    operator_name VARCHAR(255),
    service_type VARCHAR(120),

    etd TIMESTAMPTZ,
    eta TIMESTAMPTZ,

    supplier_cost NUMERIC(19,4),
    other_cost NUMERIC(19,4),
    client_revenue NUMERIC(19,4),
    amount_paid_by_client NUMERIC(19,4),
    amount_paid_to_supply NUMERIC(19,4),
    other_expenses NUMERIC(19,4),

    payment_status VARCHAR(50),

    owner_name VARCHAR(255),
    invoice_no VARCHAR(120),

    next_action TEXT,
    next_action_date DATE,

    notes TEXT,
    currency VARCHAR(10),

    airline_used VARCHAR(255),

    amount_billed_to_client NUMERIC(19,4),

    version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (tenant_id, reference_code)
);

CREATE INDEX idx_users_tenant
    ON users(tenant_id);

CREATE INDEX idx_shipments_tenant
    ON shipments(tenant_id);

CREATE INDEX idx_shipments_tenant_status
    ON shipments(tenant_id, status);

