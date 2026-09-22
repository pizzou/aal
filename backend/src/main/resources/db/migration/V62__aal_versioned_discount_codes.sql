-- Allow the same commercial discount code to have effective-dated versions.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c
      FROM pg_constraint
     WHERE conrelid='pricing_discounts'::regclass
       AND contype='u'
       AND replace(pg_get_constraintdef(oid),' ','') LIKE '%(tenant_id,discount_code)%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE pricing_discounts DROP CONSTRAINT %I', c);
    END IF;
END $$;

ALTER TABLE pricing_discounts
    ADD CONSTRAINT uq_pricing_discount_version UNIQUE (tenant_id,discount_code,valid_from);

CREATE INDEX IF NOT EXISTS ix_pricing_discount_effective
    ON pricing_discounts(tenant_id,discount_code,active,valid_from DESC,valid_until);
