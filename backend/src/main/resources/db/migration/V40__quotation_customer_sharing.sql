CREATE TABLE IF NOT EXISTS commercial_quote_shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    quote_id UUID NOT NULL REFERENCES commercial_quotes(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    opened_at TIMESTAMPTZ,
    responded_at TIMESTAMPTZ,
    response VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(token_hash)
);
CREATE INDEX IF NOT EXISTS idx_quote_shares_tenant_quote
    ON commercial_quote_shares(tenant_id, quote_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_quote_shares_token
    ON commercial_quote_shares(token_hash, expires_at);
