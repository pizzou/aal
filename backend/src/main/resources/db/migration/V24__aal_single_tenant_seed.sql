-- ============================================================================
-- Africa Logistic Aviation — Single-Tenant Production Bootstrap
-- ============================================================================
--
-- This deployment is dedicated to Africa Logistic Aviation.
--
-- There is intentionally NO public tenant/organization registration.
-- The AAL tenant and its initial administrator are provisioned here.
--
-- Initial administrator:
--   Email:    operations@africalogisticaviation.rw
--   Password: AAL@2026!ControlTower
--
-- The password is stored only as a BCrypt hash.
--
-- IMPORTANT:
-- Rotate the initial administrator credential after first production access.
-- Do not place plaintext credentials in deployment configuration.
-- ============================================================================

INSERT INTO tenants (
    id,
    name
)
VALUES (
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'Africa Logistic Aviation'
)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name;


-- ============================================================================
-- AAL ORGANIZATION PROFILE
-- ============================================================================

INSERT INTO tenant_profiles (
    tenant_id,
    legal_name,
    trading_name,
    country_code,
    city,
    timezone,
    default_currency,
    email,
    settings_json
)
VALUES (
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'Africa Logistic Aviation',
    'Africa Logistic Aviation',
    'RWA',
    'Kigali',
    'Africa/Kigali',
    'RWF',
    'operations@africalogisticaviation.rw',
    '{"clientType":"AIR_FREIGHT_FORWARDER","primaryMarket":"RWANDA","operatingModel":"AIR_CARGO_FIRST","singleTenant":true,"defaultServiceMode":"AIR","customerVisibility":true,"commercialControl":true,"documentControl":true}'
)
ON CONFLICT (tenant_id) DO UPDATE
SET
    legal_name = EXCLUDED.legal_name,
    trading_name = EXCLUDED.trading_name,
    country_code = EXCLUDED.country_code,
    city = EXCLUDED.city,
    timezone = EXCLUDED.timezone,
    default_currency = EXCLUDED.default_currency,
    email = EXCLUDED.email,
    settings_json = EXCLUDED.settings_json,
    updated_at = now();


-- ============================================================================
-- INITIAL AAL OPERATIONS ADMINISTRATOR
-- ============================================================================

INSERT INTO users (
    id,
    tenant_id,
    email,
    password_hash,
    role,
    active,
    failed_login_attempts,
    token_version
)
VALUES (
    'f4f3d8d4-4e1a-4e4a-8f2d-2c4e6f7a8b90',
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'operations@africalogisticaviation.rw',
    '$2y$12$xuidIn3aiDHYghLU1G5TD.2d0lt1W8cYUCzHWJ44COrEZL33vF74O',
    'ADMIN',
    true,
    0,
    0
)
ON CONFLICT (id) DO UPDATE
SET
    tenant_id = EXCLUDED.tenant_id,
    email = EXCLUDED.email,
    role = EXCLUDED.role,
    active = true;


-- ============================================================================
-- AAL SERVICE-LEVEL POLICIES
-- ============================================================================

INSERT INTO logistics_sla_policies (
    tenant_id,
    name,
    event_code,
    target_minutes,
    severity
)
VALUES
(
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'Booking acknowledgement',
    'BOOKING_ACK',
    60,
    'HIGH'
),
(
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'Exception response',
    'EXCEPTION_RESPONSE',
    120,
    'CRITICAL'
),
(
    '7c7d3b7c-3f20-4d3d-9c11-6f7d8f2a6b01',
    'Quote response',
    'QUOTE_RESPONSE',
    240,
    'MEDIUM'
)
ON CONFLICT (tenant_id, name, event_code) DO UPDATE
SET
    target_minutes = EXCLUDED.target_minutes,
    severity = EXCLUDED.severity,
    active = true;