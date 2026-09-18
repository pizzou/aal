-- Row-Level Security is the database-level backstop for tenant isolation.
-- Even if application code has a bug and forgets a WHERE tenant_id = ... clause,
-- Postgres itself refuses to return or modify rows outside the current session's tenant.
--
-- The app sets `app.current_tenant` per-connection at the start of each request
-- (see TenantConnectionInterceptor). If it's never set, current_setting(..., true)
-- returns NULL and every policy below denies all rows — fail closed, not open.

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_users ON users
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_shipments ON shipments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- FORCE ROW LEVEL SECURITY makes RLS apply even to the table owner (superusers still
-- always bypass RLS regardless — that's a hard Postgres rule, not configurable). This
-- was VERIFIED against a real Postgres 16 instance: with the app running as a
-- non-superuser, non-owner role (logi_app), RLS enforced tenant isolation correctly on
-- SELECT and INSERT even without FORCE; FORCE is added here as defense-in-depth in case
-- the app is ever misconfigured to run as the owning role. See scripts/create_app_role.sql
-- for the role Postgres should actually run under.
