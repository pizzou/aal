-- Universal multimodal logistics layer: air, ocean, road, rail, inland, parcel,
-- project cargo and cold-chain all share the same shipment/leg/cargo/document/rate model.
CREATE TABLE cargo_items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 line_no INTEGER NOT NULL, description VARCHAR(500) NOT NULL, package_type VARCHAR(80), quantity INTEGER NOT NULL DEFAULT 1,
 gross_weight_kg NUMERIC(19,3), volume_m3 NUMERIC(19,6), length_cm NUMERIC(19,3), width_cm NUMERIC(19,3), height_cm NUMERIC(19,3),
 hs_code VARCHAR(20), country_of_origin VARCHAR(3), declared_value NUMERIC(19,4), currency VARCHAR(3),
 dangerous_goods BOOLEAN NOT NULL DEFAULT FALSE, dg_class VARCHAR(20), un_number VARCHAR(20), temperature_controlled BOOLEAN NOT NULL DEFAULT FALSE,
 min_temperature_c NUMERIC(8,2), max_temperature_c NUMERIC(8,2), stackable BOOLEAN NOT NULL DEFAULT TRUE, fragile BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, shipment_id, line_no)
);
CREATE INDEX idx_cargo_items_tenant_shipment ON cargo_items(tenant_id, shipment_id);

CREATE TABLE transport_plan_legs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 sequence_no INTEGER NOT NULL, mode VARCHAR(30) NOT NULL, carrier_name VARCHAR(255), carrier_reference VARCHAR(120),
 origin_code VARCHAR(20), origin_name VARCHAR(255), destination_code VARCHAR(20), destination_name VARCHAR(255),
 planned_departure TIMESTAMPTZ, planned_arrival TIMESTAMPTZ, actual_departure TIMESTAMPTZ, actual_arrival TIMESTAMPTZ,
 status VARCHAR(40) NOT NULL DEFAULT 'PLANNED', equipment_type VARCHAR(80), notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, shipment_id, sequence_no)
);
CREATE INDEX idx_transport_legs_tenant_shipment ON transport_plan_legs(tenant_id, shipment_id, sequence_no);

CREATE TABLE ocean_voyages (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), vessel_name VARCHAR(255) NOT NULL, imo_number VARCHAR(20),
 voyage_number VARCHAR(80) NOT NULL, carrier_name VARCHAR(255), service_name VARCHAR(255), origin_port VARCHAR(10), destination_port VARCHAR(10),
 etd TIMESTAMPTZ, eta TIMESTAMPTZ, transshipment_ports TEXT, status VARCHAR(40) NOT NULL DEFAULT 'SCHEDULED', capacity_teu NUMERIC(19,3), booked_teu NUMERIC(19,3) NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id, carrier_name, voyage_number)
);
CREATE INDEX idx_ocean_voyages_route ON ocean_voyages(tenant_id, origin_port, destination_port, etd);

CREATE TABLE ocean_containers (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID REFERENCES shipments(id),
 container_number VARCHAR(20) NOT NULL, container_type VARCHAR(30) NOT NULL, seal_number VARCHAR(80), tare_weight_kg NUMERIC(19,3), gross_weight_kg NUMERIC(19,3),
 vgm_weight_kg NUMERIC(19,3), vgm_verified BOOLEAN NOT NULL DEFAULT FALSE, temperature_setpoint_c NUMERIC(8,2), status VARCHAR(40) NOT NULL DEFAULT 'PLANNED',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id, container_number)
);
CREATE INDEX idx_ocean_containers_shipment ON ocean_containers(tenant_id, shipment_id);

CREATE TABLE ocean_bookings (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id), voyage_id UUID REFERENCES ocean_voyages(id),
 booking_number VARCHAR(100) NOT NULL, booking_type VARCHAR(20) NOT NULL DEFAULT 'FCL', requested_containers INTEGER NOT NULL DEFAULT 1, confirmed_containers INTEGER NOT NULL DEFAULT 0,
 cutoff_document TIMESTAMPTZ, cutoff_cargo TIMESTAMPTZ, status VARCHAR(40) NOT NULL DEFAULT 'REQUESTED', carrier_reference VARCHAR(120), notes TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id, booking_number)
);

CREATE TABLE logistics_documents (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 document_type VARCHAR(60) NOT NULL, document_number VARCHAR(120), version_no INTEGER NOT NULL DEFAULT 1, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 file_uri TEXT, content_hash VARCHAR(128), issued_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, signed_by VARCHAR(255), metadata_json TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logistics_documents_shipment ON logistics_documents(tenant_id, shipment_id, document_type);

CREATE TABLE road_consignments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 cmr_number VARCHAR(120), vehicle_registration VARCHAR(40), trailer_registration VARCHAR(40), driver_name VARCHAR(255), driver_phone VARCHAR(50),
 pickup_window_start TIMESTAMPTZ, pickup_window_end TIMESTAMPTZ, delivery_window_start TIMESTAMPTZ, delivery_window_end TIMESTAMPTZ,
 status VARCHAR(40) NOT NULL DEFAULT 'PLANNED', pod_document_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, cmr_number)
);

CREATE TABLE rail_consignments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 rail_consignment_number VARCHAR(120), train_number VARCHAR(80), wagon_numbers TEXT, origin_terminal VARCHAR(255), destination_terminal VARCHAR(255),
 planned_departure TIMESTAMPTZ, planned_arrival TIMESTAMPTZ, status VARCHAR(40) NOT NULL DEFAULT 'PLANNED', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, rail_consignment_number)
);

CREATE TABLE logistics_rates (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), quote_number VARCHAR(100) NOT NULL, shipment_id UUID REFERENCES shipments(id),
 service_name VARCHAR(255) NOT NULL, mode VARCHAR(30), origin_code VARCHAR(20), destination_code VARCHAR(20), currency VARCHAR(3) NOT NULL,
 base_amount NUMERIC(19,4) NOT NULL DEFAULT 0, fuel_surcharge NUMERIC(19,4) NOT NULL DEFAULT 0, security_surcharge NUMERIC(19,4) NOT NULL DEFAULT 0,
 handling_amount NUMERIC(19,4) NOT NULL DEFAULT 0, customs_amount NUMERIC(19,4) NOT NULL DEFAULT 0, other_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
 valid_from TIMESTAMPTZ, valid_until TIMESTAMPTZ, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', terms TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, quote_number)
);

CREATE TABLE logistics_exceptions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM', exception_type VARCHAR(80) NOT NULL, title VARCHAR(255) NOT NULL, description TEXT,
 status VARCHAR(30) NOT NULL DEFAULT 'OPEN', owner VARCHAR(255), due_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, resolution TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logistics_exceptions_open ON logistics_exceptions(tenant_id, status, severity, due_at);

DO $$ DECLARE t text; BEGIN
 FOR t IN SELECT unnest(ARRAY['cargo_items','transport_plan_legs','ocean_voyages','ocean_containers','ocean_bookings','logistics_documents','road_consignments','rail_consignments','logistics_rates','logistics_exceptions']) LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
   EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)', t, t);
 END LOOP;
END $$;
