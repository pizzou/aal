-- Run this ONCE against a fresh database, as a superuser, before deploying.
-- This is the role SPRING_DATASOURCE_USERNAME/PASSWORD must point at in production.
--
-- WHY THIS MATTERS: Postgres superusers ALWAYS bypass Row-Level Security — this is a
-- hard rule with no configuration to change it. If the app connects as `postgres` (or
-- any other superuser), every RLS policy in V2/V4/V5 does nothing at all and tenant
-- isolation silently doesn't exist. This was verified directly: see the RLS
-- verification log for the exact test that confirms logi_app is correctly restricted.

CREATE ROLE logi_app LOGIN PASSWORD :'app_password'; -- pass with: psql -v app_password='...'

GRANT CONNECT ON DATABASE logiplatform TO logi_app;
GRANT USAGE ON SCHEMA public TO logi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO logi_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO logi_app;

-- Future tables created by later Flyway migrations should also grant to logi_app
-- automatically:
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO logi_app;

-- Sanity check after running this: confirm logi_app is NOT a superuser and does NOT
-- own any tables (ownership also bypasses RLS unless FORCE ROW LEVEL SECURITY is set,
-- which V2/V4/V5 now do as defense-in-depth, but don't rely on that — just don't run
-- migrations as this role).
--   SELECT rolname, rolsuper FROM pg_roles WHERE rolname = 'logi_app';  -- rolsuper must be 'f'
