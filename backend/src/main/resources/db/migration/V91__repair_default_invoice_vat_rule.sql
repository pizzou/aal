-- V91: repair the VAT configuration gap left after V85 disabled unscoped legacy rules.
-- This backfills only tenants that already have a real, active default tax jurisdiction.
-- It never creates legal entities, jurisdiction details, tax-registration numbers, or
-- rules for the inactive DEFAULT/ZZZ placeholder.
-- The standard rule is currency-neutral so it can calculate VAT in the shipment/invoice
-- currency (USD, RWF, etc.). A currency-specific rule takes precedence in the resolver.
-- The initial treatment is VAT-exclusive (18% is added to the shipment amount); finance
-- can edit the rule's inclusive flag where its approved invoicing treatment is inclusive.

INSERT INTO finance_tax_rules
    (id, tenant_id, code, tax_name, rate, withholding_rate, currency,
     valid_from, valid_until, active, jurisdiction_code, tax_type, inclusive,
     exemption_code, applies_to)
SELECT
    gen_random_uuid(),
    j.tenant_id,
    CASE
      WHEN NOT EXISTS (
          SELECT 1 FROM finance_tax_rules used
           WHERE used.tenant_id=j.tenant_id
             AND used.jurisdiction_code=j.jurisdiction_code
             AND used.code='VAT18-AUTO'
             AND used.valid_from=CURRENT_DATE
      ) THEN 'VAT18-AUTO'
      ELSE 'VAT18-AUTO-' || substr(md5(j.tenant_id::text || ':' || j.jurisdiction_code || ':' || CURRENT_DATE::text), 1, 8)
    END,
    'Value Added Tax (VAT)',
    18.0000,
    0.0000,
    NULL,
    CURRENT_DATE,
    (
      SELECT MIN(future_rule.valid_from) - 1
        FROM finance_tax_rules future_rule
       WHERE future_rule.tenant_id=j.tenant_id
         AND future_rule.jurisdiction_code=j.jurisdiction_code
         AND future_rule.active=TRUE
         AND upper(future_rule.tax_type)='VAT'
         AND future_rule.rate=18
         AND future_rule.currency IS NULL
         AND future_rule.valid_from>CURRENT_DATE
         AND (future_rule.applies_to='ALL' OR future_rule.applies_to='INVOICE')
    ),
    TRUE,
    j.jurisdiction_code,
    'VAT',
    FALSE,
    NULL,
    'INVOICE'
FROM finance_tax_jurisdictions j
WHERE j.active=TRUE
  AND j.default_for_invoicing=TRUE
  AND upper(coalesce(j.country_code,'')) <> 'ZZZ'
  AND lower(coalesce(j.legal_name,'')) NOT LIKE '%unconfigured%'
  AND NOT EXISTS (
      SELECT 1
        FROM finance_tax_rules current_rule
       WHERE current_rule.tenant_id=j.tenant_id
         AND current_rule.jurisdiction_code=j.jurisdiction_code
         AND current_rule.active=TRUE
         AND upper(current_rule.tax_type)='VAT'
         AND current_rule.rate=18
         AND current_rule.currency IS NULL
         AND current_rule.valid_from<=CURRENT_DATE
         AND (current_rule.valid_until IS NULL OR current_rule.valid_until>=CURRENT_DATE)
         AND (current_rule.applies_to='ALL' OR current_rule.applies_to='INVOICE')
  )
ON CONFLICT (tenant_id, jurisdiction_code, code, valid_from) DO NOTHING;
