-- Extend existing AWB records in place; no legacy AWB or shipment data is discarded.
ALTER TABLE awb_records
    ADD COLUMN IF NOT EXISTS airline_prefix VARCHAR(3),
    ADD COLUMN IF NOT EXISTS airline_serial VARCHAR(8),
    ADD COLUMN IF NOT EXISTS shipper_street_address TEXT,
    ADD COLUMN IF NOT EXISTS shipper_postal_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS shipper_contact_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipper_phone VARCHAR(80),
    ADD COLUMN IF NOT EXISTS shipper_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipper_tax_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS shipper_eori_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS consignee_street_address TEXT,
    ADD COLUMN IF NOT EXISTS consignee_postal_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS consignee_contact_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS consignee_phone VARCHAR(80),
    ADD COLUMN IF NOT EXISTS consignee_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS consignee_tax_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS consignee_eori_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS issuing_agent_city VARCHAR(255),
    ADD COLUMN IF NOT EXISTS iata_cargo_agent_code VARCHAR(30),
    ADD COLUMN IF NOT EXISTS agent_account_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS first_to_airport VARCHAR(3),
    ADD COLUMN IF NOT EXISTS first_by_carrier VARCHAR(2),
    ADD COLUMN IF NOT EXISTS second_to_airport VARCHAR(3),
    ADD COLUMN IF NOT EXISTS second_by_carrier VARCHAR(2),
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3),
    ADD COLUMN IF NOT EXISTS payment_terms_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS rate_class VARCHAR(10),
    ADD COLUMN IF NOT EXISTS weight_unit VARCHAR(2) NOT NULL DEFAULT 'K',
    ADD COLUMN IF NOT EXISTS rate_per_kg NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS freight_charge NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS length_cm NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS width_cm NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS height_cm NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS volumetric_weight_kg NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS nature_quantity_goods TEXT,
    ADD COLUMN IF NOT EXISTS declared_value_carriage NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS carriage_value_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS declared_value_customs NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS customs_value_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS insurance_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS routing_segments_json TEXT,
    ADD COLUMN IF NOT EXISTS cargo_rating_lines_json TEXT;

-- Keep existing city/name values untouched; only normalize airport code values on new writes.
CREATE INDEX IF NOT EXISTS idx_awb_prefix_serial ON awb_records(tenant_id, airline_prefix, airline_serial)
    WHERE airline_prefix IS NOT NULL AND airline_serial IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_awb_customs_ids ON awb_records(tenant_id, shipper_eori_number, consignee_eori_number);

-- Backfill fields that can be safely derived from legacy records without guessing.
UPDATE awb_records
   SET shipper_street_address = COALESCE(shipper_street_address, shipper_address),
       consignee_street_address = COALESCE(consignee_street_address, consignee_address),
       nature_quantity_goods = COALESCE(nature_quantity_goods, commodity)
 WHERE shipper_street_address IS NULL
    OR consignee_street_address IS NULL
    OR nature_quantity_goods IS NULL;

WITH normalized AS (
    SELECT id,
           regexp_replace(COALESCE(NULLIF(mawb_number, ''), awb_number), '[^0-9]', '', 'g') AS digits
      FROM awb_records
)
UPDATE awb_records a
   SET airline_prefix = COALESCE(a.airline_prefix, substring(n.digits FROM 1 FOR 3)),
       airline_serial = COALESCE(a.airline_serial, substring(n.digits FROM 4 FOR 8))
  FROM normalized n
 WHERE a.id = n.id
   AND length(n.digits) = 11
   AND (a.airline_prefix IS NULL OR a.airline_serial IS NULL);
