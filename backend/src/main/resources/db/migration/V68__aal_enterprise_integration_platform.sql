-- AAL enterprise control plane: credentials, provider resilience, standards,
-- reconciliation, secure webhooks, operational jobs and evidence.

CREATE TABLE IF NOT EXISTS integration_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(100) NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    protocol VARCHAR(60) NOT NULL,
    base_url TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    capabilities_json TEXT,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, code)
);
CREATE INDEX IF NOT EXISTS ix_integration_accounts_health
    ON integration_accounts(tenant_id, enabled, health_status, updated_at DESC);

CREATE TABLE IF NOT EXISTS integration_credential_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    account_id UUID NOT NULL REFERENCES integration_accounts(id) ON DELETE CASCADE,
    credential_type VARCHAR(40) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    encrypted_value TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    rotated_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account_id, credential_type, version_no)
);
CREATE INDEX IF NOT EXISTS ix_integration_credentials_expiry
    ON integration_credential_versions(tenant_id, account_id, status, expires_at);

CREATE TABLE IF NOT EXISTS integration_circuit_breakers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(100) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    failure_count INTEGER NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ,
    next_probe_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, provider_code)
);

CREATE TABLE IF NOT EXISTS integration_rate_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    scope VARCHAR(32) NOT NULL,
    provider_code VARCHAR(100),
    requests_per_minute INTEGER NOT NULL,
    burst INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, scope, provider_code)
);

CREATE TABLE IF NOT EXISTS integration_health_samples (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    latency_ms BIGINT,
    http_status INTEGER,
    error_code VARCHAR(100),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_integration_health_samples
    ON integration_health_samples(tenant_id, provider_code, observed_at DESC);

CREATE TABLE IF NOT EXISTS integration_reconciliation_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(100) NOT NULL,
    operation_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    idempotency_key VARCHAR(255),
    external_reference VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, provider_code, operation_type, aggregate_id, status)
);
CREATE INDEX IF NOT EXISTS ix_reconciliation_queue
    ON integration_reconciliation_tasks(tenant_id, status, next_attempt_at);

CREATE TABLE IF NOT EXISTS integration_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_code VARCHAR(100) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    nonce VARCHAR(255),
    signature VARCHAR(512),
    body_hash VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    error_detail TEXT,
    UNIQUE(tenant_id, provider_code, event_id),
    UNIQUE(tenant_id, provider_code, nonce)
);
CREATE INDEX IF NOT EXISTS ix_webhook_events_queue
    ON integration_webhook_events(tenant_id, provider_code, status, received_at DESC);

CREATE TABLE IF NOT EXISTS integration_job_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    job_name VARCHAR(160) NOT NULL,
    execution_key VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    attempt_count INTEGER NOT NULL DEFAULT 1,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ,
    error_detail TEXT,
    UNIQUE(tenant_id, job_name, execution_key)
);
CREATE INDEX IF NOT EXISTS ix_job_executions_queue
    ON integration_job_executions(tenant_id, job_name, status, started_at DESC);

CREATE TABLE IF NOT EXISTS booking_state_transitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    booking_id UUID NOT NULL REFERENCES air_cargo_bookings(id) ON DELETE CASCADE,
    from_state VARCHAR(40),
    to_state VARCHAR(40) NOT NULL,
    reason VARCHAR(160),
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_booking_state_history
    ON booking_state_transitions(tenant_id, booking_id, created_at DESC);

CREATE TABLE IF NOT EXISTS onerecord_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    object_reference TEXT NOT NULL,
    object_type VARCHAR(120),
    api_version VARCHAR(40) NOT NULL,
    ontology_version VARCHAR(40),
    json_ld TEXT NOT NULL,
    etag VARCHAR(255),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, object_reference)
);

CREATE TABLE IF NOT EXISTS onerecord_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    account_id UUID REFERENCES integration_accounts(id),
    subscription_reference VARCHAR(255) NOT NULL,
    object_type VARCHAR(120),
    callback_url TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    provider_subscription_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, subscription_reference)
);

CREATE TABLE IF NOT EXISTS onerecord_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_id VARCHAR(255) NOT NULL,
    object_reference TEXT,
    event_type VARCHAR(160) NOT NULL,
    api_version VARCHAR(40) NOT NULL,
    payload_jsonld TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    UNIQUE(tenant_id, event_id)
);

CREATE TABLE IF NOT EXISTS onerecord_access_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    object_reference TEXT NOT NULL,
    grantee VARCHAR(255) NOT NULL,
    permission VARCHAR(40) NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cargo_xml_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    message_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(120) NOT NULL,
    standard_version VARCHAR(40) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    payload_xml TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    validation_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    UNIQUE(tenant_id, message_id)
);
CREATE INDEX IF NOT EXISTS ix_cargo_xml_messages_queue
    ON cargo_xml_messages(tenant_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS financial_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    operation_key VARCHAR(255) NOT NULL,
    operation_type VARCHAR(80) NOT NULL,
    source_type VARCHAR(80) NOT NULL,
    source_id UUID,
    original_amount NUMERIC(19,4) NOT NULL,
    original_currency VARCHAR(3) NOT NULL,
    exchange_rate NUMERIC(24,12),
    rate_source VARCHAR(120),
    rate_date DATE,
    functional_amount NUMERIC(19,4),
    functional_currency VARCHAR(3),
    status VARCHAR(32) NOT NULL DEFAULT 'POSTED',
    reversal_of UUID REFERENCES financial_operations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, operation_key)
);
CREATE INDEX IF NOT EXISTS ix_financial_operations_source
    ON financial_operations(tenant_id, source_type, source_id, created_at DESC);

CREATE TABLE IF NOT EXISTS document_access_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_id UUID NOT NULL REFERENCES cargo_documents(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id),
    action VARCHAR(40) NOT NULL,
    ip_address VARCHAR(64),
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_document_access_audit
    ON document_access_audit(tenant_id, document_id, created_at DESC);

CREATE TABLE IF NOT EXISTS api_idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    idempotency_key VARCHAR(255) NOT NULL,
    method VARCHAR(12) NOT NULL,
    request_path TEXT NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(tenant_id, idempotency_key)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'integration_accounts','integration_credential_versions','integration_circuit_breakers',
        'integration_rate_limits','integration_health_samples','integration_reconciliation_tasks',
        'integration_webhook_events','integration_job_executions','booking_state_transitions',
        'onerecord_objects','onerecord_subscriptions','onerecord_events','onerecord_access_grants',
        'cargo_xml_messages','financial_operations','document_access_audit','api_idempotency_keys'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I', t, t);
        EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)', t, t);
    END LOOP;
END $$;
