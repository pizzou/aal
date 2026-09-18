-- Load planning needs to know what it's optimizing. weight_kg is nullable because
-- a shipment can exist before its weight is known (e.g. booked before cargo is
-- weighed at the warehouse) — load planning simply excludes unweighed shipments
-- from consideration rather than forcing a fake value.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS weight_kg INTEGER;
ALTER TABLE shipments DROP CONSTRAINT IF EXISTS chk_weight_non_negative;
ALTER TABLE shipments ADD CONSTRAINT chk_weight_non_negative CHECK (weight_kg IS NULL OR weight_kg >= 0);
