CREATE TABLE warehouses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(255) NOT NULL,
    address TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE inventory_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0,
    reorder_level INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, warehouse_id, sku),
    CONSTRAINT chk_quantity_non_negative CHECK (quantity_on_hand >= 0)
);

-- Append-only ledger: every change to quantity_on_hand must be traceable to a
-- movement row here. Never update quantity_on_hand directly from application code
-- without inserting the matching movement in the same transaction (see StockMovementService).
CREATE TABLE stock_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    inventory_item_id UUID NOT NULL REFERENCES inventory_items(id),
    movement_type VARCHAR(20) NOT NULL, -- IN, OUT, ADJUSTMENT
    quantity INTEGER NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_warehouses_tenant ON warehouses(tenant_id);
CREATE INDEX idx_inventory_tenant_warehouse ON inventory_items(tenant_id, warehouse_id);
CREATE INDEX idx_movements_tenant_item ON stock_movements(tenant_id, inventory_item_id);
