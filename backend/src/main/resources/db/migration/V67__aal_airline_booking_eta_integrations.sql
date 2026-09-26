-- AAL airline connectivity, ETA history, idempotent operations and dead-letter support.

/* ============================================================
   1. AIR CARGO BOOKINGS
   ============================================================ */

ALTER TABLE air_cargo_bookings
    ADD COLUMN IF NOT EXISTS service_level VARCHAR(80),
    ADD COLUMN IF NOT EXISTS cancellation_reason TEXT,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_operation_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_provider_operation VARCHAR(80);


CREATE INDEX IF NOT EXISTS ix_air_booking_provider_ref
    ON air_cargo_bookings (
        tenant_id,
        provider,
        provider_reference
    );


CREATE INDEX IF NOT EXISTS ix_air_booking_status
    ON air_cargo_bookings (
        tenant_id,
        status,
        updated_at DESC
    );


CREATE UNIQUE INDEX IF NOT EXISTS ux_air_booking_operation_key
    ON air_cargo_bookings (
        tenant_id,
        last_operation_key
    )
    WHERE last_operation_key IS NOT NULL;


/* ============================================================
   2. SHIPMENT ETA HISTORY
   ============================================================ */

CREATE TABLE IF NOT EXISTS shipment_eta_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL
        REFERENCES tenants(id),

    shipment_id UUID NOT NULL
        REFERENCES shipments(id)
        ON DELETE CASCADE,

    source VARCHAR(80) NOT NULL,

    provider_event_id VARCHAR(160),

    flight_number VARCHAR(80),

    flight_status VARCHAR(60),

    previous_etd TIMESTAMPTZ,

    new_etd TIMESTAMPTZ,

    previous_eta TIMESTAMPTZ,

    new_eta TIMESTAMPTZ,

    reason VARCHAR(120),

    raw_payload_hash VARCHAR(128),

    observed_at TIMESTAMPTZ NOT NULL
        DEFAULT now(),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT now()
);


CREATE INDEX IF NOT EXISTS ix_shipment_eta_history_lookup
    ON shipment_eta_history (
        tenant_id,
        shipment_id,
        observed_at DESC
    );


/* ============================================================
   3. AIRLINE INTEGRATION DEAD LETTERS
   ============================================================ */

CREATE TABLE IF NOT EXISTS airline_integration_dead_letters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL
        REFERENCES tenants(id),

    provider VARCHAR(80) NOT NULL,

    operation VARCHAR(100) NOT NULL,

    idempotency_key VARCHAR(160),

    correlation_id VARCHAR(160),

    payload TEXT,

    error_detail TEXT NOT NULL,

    attempts INTEGER NOT NULL
        DEFAULT 1,

    status VARCHAR(30) NOT NULL
        DEFAULT 'OPEN',

    last_attempt_at TIMESTAMPTZ NOT NULL
        DEFAULT now(),

    resolved_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT now()
);


CREATE INDEX IF NOT EXISTS ix_airline_dead_letters_queue
    ON airline_integration_dead_letters (
        tenant_id,
        status,
        last_attempt_at DESC
    );


/* ============================================================
   4. INTEGRATION ATTEMPT STATUS INDEX
   ============================================================ */

-- IMPORTANT:
-- integration_attempts uses integration_code.
-- It does NOT have a provider column.

CREATE INDEX IF NOT EXISTS ix_integration_attempts_integration_status
    ON integration_attempts (
        tenant_id,
        integration_code,
        operation,
        status,
        created_at DESC
    );


/* ============================================================
   5. RLS - ETA HISTORY + DEAD LETTERS
   ============================================================ */

DO $$
DECLARE
    t TEXT;
BEGIN

    FOREACH t IN ARRAY ARRAY[
        'shipment_eta_history',
        'airline_integration_dead_letters'
    ]
    LOOP

        EXECUTE format(
            'ALTER TABLE %I ENABLE ROW LEVEL SECURITY',
            t
        );

        EXECUTE format(
            'ALTER TABLE %I FORCE ROW LEVEL SECURITY',
            t
        );

        EXECUTE format(
            'DROP POLICY IF EXISTS tenant_isolation_%I ON %I',
            t,
            t
        );

        EXECUTE format(
            'CREATE POLICY tenant_isolation_%I ON %I
             USING (
                 tenant_id =
                 NULLIF(
                     current_setting(''app.current_tenant'', true),
                     ''''
                 )::uuid
             )
             WITH CHECK (
                 tenant_id =
                 NULLIF(
                     current_setting(''app.current_tenant'', true),
                     ''''
                 )::uuid
             )',
            t,
            t
        );

    END LOOP;

END
$$;


/* ============================================================
   6. AIRLINE INTEGRATION ALERTS
   ============================================================ */

CREATE TABLE IF NOT EXISTS airline_integration_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL
        REFERENCES tenants(id),

    alert_type VARCHAR(60) NOT NULL,

    subject_ref VARCHAR(160) NOT NULL,

    message TEXT NOT NULL,

    status VARCHAR(20) NOT NULL
        DEFAULT 'OPEN',

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT now(),

    resolved_at TIMESTAMPTZ,

    UNIQUE (
        tenant_id,
        alert_type,
        subject_ref,
        status
    )
);


CREATE INDEX IF NOT EXISTS ix_airline_integration_alerts_queue
    ON airline_integration_alerts (
        tenant_id,
        status,
        created_at DESC
    );


/* ============================================================
   7. RLS - AIRLINE INTEGRATION ALERTS
   ============================================================ */

ALTER TABLE airline_integration_alerts
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE airline_integration_alerts
    FORCE ROW LEVEL SECURITY;


DROP POLICY IF EXISTS tenant_isolation_airline_integration_alerts
    ON airline_integration_alerts;


CREATE POLICY tenant_isolation_airline_integration_alerts
    ON airline_integration_alerts

    USING (
        tenant_id =
        NULLIF(
            current_setting('app.current_tenant', true),
            ''
        )::uuid
    )

    WITH CHECK (
        tenant_id =
        NULLIF(
            current_setting('app.current_tenant', true),
            ''
        )::uuid
    );


/* ============================================================
   8. AIR CARGO BOOKING IDEMPOTENCY
   ============================================================ */

CREATE UNIQUE INDEX IF NOT EXISTS ux_air_booking_idempotency
    ON air_cargo_bookings (
        tenant_id,
        idempotency_key
    );