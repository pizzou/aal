-- Enterprise integration auditability and deployment-readiness evidence.
--
-- IMPORTANT:
-- integration_attempts is already part of the AAL migration history.
-- Its canonical provider identifier is integration_code, not provider.
-- Its canonical HTTP response field is response_code, not http_status.
--
-- This migration is therefore written to be compatible with an existing
-- integration_attempts table while adding the additional audit evidence
-- required by the enterprise integration layer.

CREATE TABLE IF NOT EXISTS integration_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    integration_code VARCHAR(80) NOT NULL,
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    response_code INTEGER,
    error_detail TEXT,
    attempts INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The earlier integration_attempts schema is authoritative for the
-- existing columns. Add only the additional auditability fields here.

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS direction VARCHAR(16);

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(160);

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(128);

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS response_hash VARCHAR(128);

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

ALTER TABLE integration_attempts
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

-- Existing rows may predate started_at. Backfill them from created_at
-- before applying any application-level assumptions about the field.

UPDATE integration_attempts
SET started_at = COALESCE(started_at, created_at)
WHERE started_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_integration_attempts_tenant_time
    ON integration_attempts(
        tenant_id,
        created_at DESC
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_integration_attempts_tenant_key
    ON integration_attempts(
        tenant_id,
        integration_code,
        operation,
        idempotency_key
    )
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
    ON edi_messages(
        tenant_id,
        created_at DESC
    );


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
    ON webhook_delivery_attempts(
        tenant_id,
        created_at DESC
    );


DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'integration_attempts',
        'edi_messages',
        'webhook_delivery_attempts'
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