-- Permit multiple effective-dated versions of one customer rate-card code.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c
      FROM pg_constraint
     WHERE conrelid='customer_rate_cards'::regclass
       AND contype='u'
       AND replace(pg_get_constraintdef(oid),' ','') LIKE '%(tenant_id,client_id,card_code)%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customer_rate_cards DROP CONSTRAINT %I', c);
    ELSE
        SELECT conname INTO c
          FROM pg_constraint
         WHERE conrelid='customer_rate_cards'::regclass
           AND contype='u'
           AND replace(pg_get_constraintdef(oid),' ','') LIKE '%(tenant_id,card_code)%';
        IF c IS NOT NULL THEN
            EXECUTE format('ALTER TABLE customer_rate_cards DROP CONSTRAINT %I', c);
        END IF;
    END IF;
END $$;

ALTER TABLE customer_rate_cards
    ADD CONSTRAINT uq_customer_rate_card_version UNIQUE (tenant_id,card_code,valid_from);

CREATE INDEX IF NOT EXISTS ix_customer_rate_cards_effective
    ON customer_rate_cards(tenant_id,client_id,mode,lane_code,active,valid_from DESC,valid_until);
