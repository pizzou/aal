-- Consolidate duplicate shipment revenue storage.
-- amount_billed_to_client is the canonical AAL revenue field.
UPDATE shipments
   SET amount_billed_to_client = COALESCE(amount_billed_to_client, client_revenue)
 WHERE client_revenue IS NOT NULL;

ALTER TABLE shipments DROP COLUMN IF EXISTS client_revenue;
