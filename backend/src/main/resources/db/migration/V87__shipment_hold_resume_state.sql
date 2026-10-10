-- Preserve the stage interrupted by a manual hold so an in-transit shipment cannot
-- resume directly into final-mile delivery without arriving at a route destination.
ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS status_before_hold VARCHAR(40);

COMMENT ON COLUMN shipments.status_before_hold IS
    'Validated operational status immediately before ON_HOLD; NULL for legacy/imported holds whose prior state is unknown.';
