-- An accepted quotation has one canonical shipment; old converted quotes stay unlinked
-- and must be reconciled manually rather than silently converted twice.
ALTER TABLE commercial_quotes
    ADD COLUMN IF NOT EXISTS converted_shipment_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_quotes_tenant_shipment
    ON commercial_quotes(tenant_id, converted_shipment_id)
    WHERE converted_shipment_id IS NOT NULL;
