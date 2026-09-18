-- Nullable and only meaningful for AIR shipments — the flight number (e.g. "KQ100")
-- that a real flight-status API is queried against. Separate from
-- carrier_reference_number, which holds the AWB number for AIR shipments, not the
-- flight number — an AWB can move across multiple flights/legs in reality, though
-- this platform's Shipment model (see README's honest correction on multi-modal
-- leg-splitting) only tracks one flight number per shipment today.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS flight_number VARCHAR(20);
