-- backend/src/main/resources/db/migration/V68__aal_enterprise_integration_platform.sql

CREATE TABLE IF NOT EXISTS integration_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(120) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    environment VARCHAR(30) NOT NULL DEFAULT 'PRODUCTION',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    base_url TEXT,
    health_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    last_health_check_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    failure_count INTEGER NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, provider_code, environment)
);

CREATE INDEX IF NOT EXISTS ix_integration_accounts_tenant_status
    ON integration_accounts(tenant_id, status, provider_code);

CREATE TABLE IF NOT EXISTS integration_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_account_id UUID NOT NULL
        REFERENCES integration_accounts(id) ON DELETE CASCADE,
    credential_type VARCHAR(40) NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    encrypted_client_id TEXT,
    encrypted_client_secret TEXT,
    encrypted_api_key TEXT,
    encrypted_refresh_token TEXT,
    encrypted_private_key TEXT,
    key_id VARCHAR(160),
    expires_at TIMESTAMPTZ,
    rotated_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (integration_account_id, credential_type, version)
);

CREATE INDEX IF NOT EXISTS ix_integration_credentials_expiry
    ON integration_credentials(status, expires_at);

CREATE TABLE IF NOT EXISTS integration_provider_health (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_account_id UUID NOT NULL
        REFERENCES integration_accounts(id) ON DELETE CASCADE,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(30) NOT NULL,
    latency_ms BIGINT,
    http_status INTEGER,
    error_code VARCHAR(120),
    error_detail TEXT
);

CREATE INDEX IF NOT EXISTS ix_integration_provider_health_lookup
    ON integration_provider_health(
        integration_account_id,
        checked_at DESC
    );

CREATE TABLE IF NOT EXISTS integration_reconciliation_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    integration_account_id UUID
        REFERENCES integration_accounts(id),
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255),
    external_reference VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_integration_reconciliation_queue
    ON integration_reconciliation_jobs(
        status,
        next_attempt_at
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_integration_reconciliation_idempotency
    ON integration_reconciliation_jobs(
        tenant_id,
        operation,
        idempotency_key
    )
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS integration_webhook_replay_guard (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(120) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    nonce VARCHAR(255),
    payload_hash VARCHAR(128) NOT NULL,
    signature VARCHAR(512),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    UNIQUE(tenant_id, provider_code, event_id),
    UNIQUE(tenant_id, provider_code, nonce)
);

CREATE INDEX IF NOT EXISTS ix_webhook_replay_guard_hash
    ON integration_webhook_replay_guard(
        provider_code,
        payload_hash,
        received_at DESC
    );

CREATE TABLE IF NOT EXISTS integration_rate_limit_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_account_id UUID NOT NULL
        REFERENCES integration_accounts(id) ON DELETE CASCADE,
    operation VARCHAR(100) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    limit_per_window BIGINT NOT NULL,
    window_seconds INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(
        integration_account_id,
        operation,
        window_started_at
    )
);

CREATE TABLE IF NOT EXISTS onerecord_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    object_uri TEXT NOT NULL,
    object_type VARCHAR(160),
    ontology_version VARCHAR(40) NOT NULL,
    api_version VARCHAR(40),
    json_ld JSONB NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    version_number BIGINT NOT NULL DEFAULT 1,
    source_provider VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, object_uri, version_number)
);

CREATE INDEX IF NOT EXISTS ix_onerecord_objects_uri
    ON onerecord_objects(tenant_id, object_uri);

CREATE TABLE IF NOT EXISTS onerecord_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(120) NOT NULL,
    subscription_uri TEXT,
    target_url TEXT NOT NULL,
    object_type VARCHAR(160),
    event_type VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    secret_reference VARCHAR(255),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_onerecord_subscriptions_queue
    ON onerecord_subscriptions(
        tenant_id,
        status,
        expires_at
    );

CREATE TABLE IF NOT EXISTS cargo_xml_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(120),
    message_type VARCHAR(160) NOT NULL,
    standard_version VARCHAR(40) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    message_id VARCHAR(255),
    correlation_id VARCHAR(255),
    payload_hash VARCHAR(128) NOT NULL,
    xml_payload TEXT NOT NULL,
    validation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    error_code VARCHAR(120),
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_cargo_xml_messages_lookup
    ON cargo_xml_messages(
        tenant_id,
        message_type,
        created_at DESC
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_cargo_xml_message_id
    ON cargo_xml_messages(
        tenant_id,
        message_id
    )
    WHERE message_id IS NOT NULL;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'integration_accounts',
        'integration_credentials',
        'integration_provider_health',
        'integration_reconciliation_jobs',
        'integration_webhook_replay_guard',
        'integration_rate_limit_state',
        'onerecord_objects',
        'onerecord_subscriptions',
        'cargo_xml_messages'
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