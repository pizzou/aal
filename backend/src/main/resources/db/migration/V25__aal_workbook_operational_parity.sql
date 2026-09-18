-- ============================================================================
-- AAL WORKBOOK OPERATIONAL PARITY
-- ============================================================================
--
-- Makes the AAL workbook's "Date Opened" a first-class shipment field.
--
-- Existing workbook financial values such as:
--
--   Amount Billed
--   Amount Paid
--   Amount Remaining
--   Amount Paid to Supply
--   Other Expenses
--   Net Income
--
-- are already represented by the Shipment domain and are calculated by Java.
--
-- The database therefore stores source financial inputs while derived values
-- remain server-calculated.
-- ============================================================================

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS date_opened DATE;

-- Existing shipments receive a sensible historical value.
-- For migrated records this is their original database creation date.
UPDATE shipments
SET date_opened = created_at::date
WHERE date_opened IS NULL;