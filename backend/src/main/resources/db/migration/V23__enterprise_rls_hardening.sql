


DO $$
DECLARE
    table_name text;
BEGIN
    FOR table_name IN
        SELECT unnest(ARRAY[
            'air_cargo_flights',
            'air_cargo_bookings',
            'transport_legs',
            'awb_records',
            'customs_declarations',
            'cargo_documents',
            'commercial_quotes',
            'commercial_invoices',
            'client_records',
            'partner_records',
            'task_records',
            'expense_records',
            'iot_devices',
            'cargo_items',
            'transport_plan_legs',
            'ocean_voyages',
            'ocean_containers',
            'ocean_bookings',
            'logistics_documents',
            'road_consignments',
            'rail_consignments',
            'logistics_rates',
            'logistics_exceptions',
            'tenant_profiles',
            'logistics_contacts',
            'shipment_parties',
            'logistics_quotes',
            'logistics_quote_lines',
            'shipment_milestones',
            'logistics_sla_policies',
            'logistics_workflows',
            'logistics_tasks',
            'integration_outbox',
            'logistics_claims',
            'shipment_insurance'
        ])
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ENABLE ROW LEVEL SECURITY',
            table_name
        );

        EXECUTE format(
            'ALTER TABLE %I FORCE ROW LEVEL SECURITY',
            table_name
        );

        EXECUTE format(
            'DROP POLICY IF EXISTS tenant_isolation_%I ON %I',
            table_name,
            table_name
        );

        EXECUTE format(
            'CREATE POLICY tenant_isolation_%I ON %I ' ||
            'USING (' ||
                'tenant_id = NULLIF(' ||
                    'current_setting(''app.current_tenant'', true), ' ||
                    '''''' ||
                ')::uuid' ||
            ') ' ||
            'WITH CHECK (' ||
                'tenant_id = NULLIF(' ||
                    'current_setting(''app.current_tenant'', true), ' ||
                    '''''' ||
                ')::uuid' ||
            ')',
            table_name,
            table_name
        );
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- High-value enterprise lookup paths.
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_air_flights_tenant_status_departure
    ON air_cargo_flights(
        tenant_id,
        status,
        departure_time
    );

CREATE INDEX IF NOT EXISTS idx_air_bookings_tenant_shipment
    ON air_cargo_bookings(
        tenant_id,
        shipment_id,
        created_at DESC
    );

-- transport_legs intentionally has no created_at column.
--
-- Its schema already defines:
--   UNIQUE(tenant_id, shipment_id, sequence_no)
--
-- PostgreSQL creates an index for that unique constraint, so adding another
-- tenant/shipment index here would be redundant. Do not introduce a fake
-- created_at column merely for indexing.

CREATE INDEX IF NOT EXISTS idx_awb_records_tenant_shipment
    ON awb_records(
        tenant_id,
        shipment_id,
        created_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_customs_declarations_tenant_status
    ON customs_declarations(
        tenant_id,
        status,
        submitted_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_commercial_quotes_tenant_status
    ON commercial_quotes(
        tenant_id,
        status,
        quote_date DESC
    );

CREATE INDEX IF NOT EXISTS idx_commercial_invoices_tenant_due
    ON commercial_invoices(
        tenant_id,
        due_date,
        amount_paid
    );

CREATE INDEX IF NOT EXISTS idx_client_records_tenant_status
    ON client_records(
        tenant_id,
        client_status,
        client_company
    );

CREATE INDEX IF NOT EXISTS idx_partner_records_tenant_status
    ON partner_records(
        tenant_id,
        status,
        company
    );

CREATE INDEX IF NOT EXISTS idx_tasks_tenant_status_due
    ON logistics_tasks(
        tenant_id,
        status,
        due_at
    );

CREATE INDEX IF NOT EXISTS idx_outbox_tenant_queue
    ON integration_outbox(
        tenant_id,
        status,
        next_attempt_at
    );

CREATE INDEX IF NOT EXISTS idx_claims_tenant_status
    ON logistics_claims(
        tenant_id,
        status,
        created_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_insurance_tenant_status
    ON shipment_insurance(
        tenant_id,
        status,
        created_at DESC
    );

