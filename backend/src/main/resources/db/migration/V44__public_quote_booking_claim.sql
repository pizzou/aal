-- Atomic booking claims prevent two concurrent public requests from creating
-- two shipments for one public quotation.

ALTER TABLE public_quote_requests
    ADD COLUMN IF NOT EXISTS booking_claimed_at TIMESTAMPTZ;

ALTER TABLE commercial_quote_shares
    ADD COLUMN IF NOT EXISTS booking_claimed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_public_quote_requests_booking_claim
    ON public_quote_requests(status, booking_claimed_at)
    WHERE status = 'BOOKING';

CREATE INDEX IF NOT EXISTS idx_commercial_quote_shares_booking_claim
    ON commercial_quote_shares(booking_claimed_at)
    WHERE booked_shipment_id IS NULL;
