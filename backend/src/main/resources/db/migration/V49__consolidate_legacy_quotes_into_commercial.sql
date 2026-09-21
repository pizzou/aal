-- Canonicalize the pre-commercial enterprise quote tables.
-- Sales & Quotations is the only operational quote store.
-- Existing legacy quote rows are migrated before the duplicate tables are removed.

INSERT INTO commercial_quotes (
    id, tenant_id, quote_id, quote_date, client, route, service_type, commodity,
    chargeable_weight_kg, supplier_cost, other_cost, markup_percent, quoted_amount,
    expected_profit, valid_until, status, owner, follow_up_date, notes, pricing_mode, created_at
)
SELECT
    lq.id,
    lq.tenant_id,
    lq.quote_number,
    COALESCE(lq.created_at::date, CURRENT_DATE),
    cr.client_company,
    NULL,
    NULL,
    NULL,
    0,
    COALESCE(lines.supplier_cost, 0),
    0,
    COALESCE(lq.target_margin_percent, 0),
    COALESCE(lq.total_amount, 0),
    COALESCE(lq.total_amount, 0) - COALESCE(lines.supplier_cost, 0),
    lq.valid_until::date,
    COALESCE(lq.status, 'DRAFT'),
    NULL,
    NULL,
    NULLIF(lq.terms, ''),
    'LEGACY_ENTERPRISE_MIGRATION',
    COALESCE(lq.created_at, now())
FROM logistics_quotes lq
LEFT JOIN client_records cr
       ON cr.id = lq.client_id
      AND cr.tenant_id = lq.tenant_id
LEFT JOIN (
    SELECT tenant_id, quote_id,
           SUM(COALESCE(cost_amount, 0)) AS supplier_cost
    FROM logistics_quote_lines
    GROUP BY tenant_id, quote_id
) lines
       ON lines.tenant_id = lq.tenant_id
      AND lines.quote_id = lq.id
WHERE NOT EXISTS (
    SELECT 1
    FROM commercial_quotes cq
    WHERE cq.tenant_id = lq.tenant_id
      AND cq.quote_id = lq.quote_number
);

-- Preserve the operational canonical model going forward.
DROP TABLE IF EXISTS logistics_quote_lines;
DROP TABLE IF EXISTS logistics_quotes;
