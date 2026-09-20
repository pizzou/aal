-- Production integrity constraints added without modifying any released migration.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public_quote_requests
        WHERE booked_shipment_id IS NOT NULL
        GROUP BY booked_shipment_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot create unique public quote booking index: duplicate booked_shipment_id values exist';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'uq_public_quote_requests_booked_shipment'
    ) THEN
        CREATE UNIQUE INDEX uq_public_quote_requests_booked_shipment
            ON public_quote_requests(booked_shipment_id)
            WHERE booked_shipment_id IS NOT NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_public_quote_requests_status_expiry
    ON public_quote_requests(status, expires_at);

CREATE INDEX IF NOT EXISTS idx_commercial_quote_shares_status_expiry
    ON commercial_quote_shares(response, expires_at);

CREATE INDEX IF NOT EXISTS idx_notification_queue_ready
    ON notification_queue(tenant_id, status, next_attempt_at, created_at);
