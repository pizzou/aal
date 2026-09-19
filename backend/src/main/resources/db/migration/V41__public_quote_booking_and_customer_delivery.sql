ALTER TABLE commercial_quotes
    ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS customer_contact_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(80);

ALTER TABLE commercial_quote_shares
    ADD COLUMN IF NOT EXISTS booked_shipment_id UUID REFERENCES shipments(id),
    ADD COLUMN IF NOT EXISTS booked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_quote_shares_booked_shipment
    ON commercial_quote_shares(booked_shipment_id)
    WHERE booked_shipment_id IS NOT NULL;
