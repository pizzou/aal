CREATE ROLE aal_app LOGIN PASSWORD :'app_password';

GRANT CONNECT ON DATABASE aal TO aal_app;
GRANT USAGE ON SCHEMA public TO aal_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO aal_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO aal_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aal_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO aal_app;

-- Verify after creation:
-- SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname='aal_app';
-- Expected: rolsuper=false, rolbypassrls=false.
