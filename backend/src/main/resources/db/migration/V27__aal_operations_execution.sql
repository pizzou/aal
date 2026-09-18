-- AAL operational execution layer: dispatch stops, POD, exceptions, warehouse execution and carrier tendering.
CREATE TABLE IF NOT EXISTS dispatch_stops (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), trip_id UUID NOT NULL REFERENCES trips(id), shipment_id UUID REFERENCES shipments(id),
 sequence_no INTEGER NOT NULL CHECK(sequence_no>0), stop_type VARCHAR(40) NOT NULL, address VARCHAR(500) NOT NULL, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
 geofence_radius_m INTEGER NOT NULL DEFAULT 500, planned_at TIMESTAMPTZ, eta TIMESTAMPTZ, actual_at TIMESTAMPTZ, status VARCHAR(30) NOT NULL DEFAULT 'PLANNED',
 distance_from_previous_km NUMERIC(12,3), planned_duration_minutes INTEGER, notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,trip_id,sequence_no)
);
CREATE INDEX IF NOT EXISTS idx_dispatch_stops_trip ON dispatch_stops(tenant_id,trip_id,sequence_no);
CREATE TABLE IF NOT EXISTS proof_of_delivery (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id), trip_id UUID REFERENCES trips(id),
 recipient_name VARCHAR(255) NOT NULL, recipient_phone VARCHAR(80), signature_uri TEXT, photo_uri TEXT, delivered_at TIMESTAMPTZ NOT NULL, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
 failure_reason TEXT, notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,shipment_id)
);
CREATE TABLE IF NOT EXISTS operational_exceptions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID REFERENCES shipments(id), trip_id UUID REFERENCES trips(id), vehicle_id UUID REFERENCES vehicles(id),
 type VARCHAR(80) NOT NULL, severity VARCHAR(20) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'OPEN', owner VARCHAR(255), description TEXT NOT NULL, action_taken TEXT, resolution TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_operational_exceptions_queue ON operational_exceptions(tenant_id,status,severity,created_at);
CREATE TABLE IF NOT EXISTS warehouse_tasks (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), warehouse_id UUID NOT NULL REFERENCES warehouses(id), shipment_id UUID REFERENCES shipments(id), inventory_item_id UUID REFERENCES inventory_items(id),
 task_type VARCHAR(60) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'OPEN', quantity INTEGER, source_location VARCHAR(255), destination_location VARCHAR(255), assigned_to VARCHAR(255), due_at TIMESTAMPTZ,
 completed_at TIMESTAMPTZ, notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_warehouse_tasks_queue ON warehouse_tasks(tenant_id,warehouse_id,status,due_at);
CREATE TABLE IF NOT EXISTS carrier_tenders (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID REFERENCES shipments(id), trip_id UUID REFERENCES trips(id), tender_key VARCHAR(255) NOT NULL,
 carrier_name VARCHAR(255) NOT NULL, carrier_contact VARCHAR(255), quoted_amount NUMERIC(19,4), currency VARCHAR(10) NOT NULL DEFAULT 'USD', status VARCHAR(30) NOT NULL DEFAULT 'SENT', expires_at TIMESTAMPTZ,
 response_note TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), responded_at TIMESTAMPTZ, UNIQUE(tenant_id,tender_key)
);
CREATE INDEX IF NOT EXISTS idx_carrier_tenders_queue ON carrier_tenders(tenant_id,status,expires_at);
DO $$ DECLARE t text; BEGIN FOR t IN SELECT unnest(ARRAY['dispatch_stops','proof_of_delivery','operational_exceptions','warehouse_tasks','carrier_tenders']) LOOP EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t); EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t); EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t); END LOOP; END $$;
