-- Run only after the application has created the AAL tenant through the normal
-- registration/provisioning workflow. This script contains NO credentials.
-- It is intentionally safe to run repeatedly.
INSERT INTO tenant_profiles(tenant_id,legal_name,trading_name,country_code,city,timezone,default_currency)
SELECT id,'Africa Aviation Logistic','Africa Aviation Logistic','RWA','Kigali','Africa/Kigali','USD'
FROM tenants WHERE lower(name)=lower('Africa Aviation Logistic')
ON CONFLICT (tenant_id) DO UPDATE SET legal_name=EXCLUDED.legal_name,trading_name=EXCLUDED.trading_name,country_code=EXCLUDED.country_code,city=EXCLUDED.city,timezone=EXCLUDED.timezone,default_currency=EXCLUDED.default_currency,updated_at=now();

INSERT INTO logistics_sla_policies(tenant_id,name,event_code,target_minutes,severity)
SELECT id,'Booking acknowledgement','BOOKING_ACK',60,'HIGH' FROM tenants WHERE lower(name)=lower('Africa Aviation Logistic')
ON CONFLICT (tenant_id,name,event_code) DO NOTHING;
INSERT INTO logistics_sla_policies(tenant_id,name,event_code,target_minutes,severity)
SELECT id,'Exception response','EXCEPTION_RESPONSE',120,'CRITICAL' FROM tenants WHERE lower(name)=lower('Africa Aviation Logistic')
ON CONFLICT (tenant_id,name,event_code) DO NOTHING;
INSERT INTO logistics_sla_policies(tenant_id,name,event_code,target_minutes,severity)
SELECT id,'Quote response','QUOTE_RESPONSE',240,'MEDIUM' FROM tenants WHERE lower(name)=lower('Africa Aviation Logistic')
ON CONFLICT (tenant_id,name,event_code) DO NOTHING;
