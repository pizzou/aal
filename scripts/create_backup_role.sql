-- Required for scripts/backup.sh to work at all. Discovered by actually running
-- pg_dump against this schema: the normal app role (logi_app) is correctly blocked
-- by Row-Level Security from dumping any RLS-protected table, since a backup isn't
-- scoped to one tenant. A dedicated role with BYPASSRLS is needed — NOT the app
-- role, and deliberately NOT a full superuser either (least privilege).

CREATE ROLE logi_backup LOGIN PASSWORD :'backup_password' BYPASSRLS;

GRANT CONNECT ON DATABASE logiplatform TO logi_backup;
GRANT USAGE ON SCHEMA public TO logi_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO logi_backup;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO logi_backup;

-- Verified directly: with this role, `pg_dump` succeeds and captures ALL tenants'
-- data (as a backup must), and RLS policies themselves are preserved in the dump
-- and restored correctly into a fresh database. See RLS_VERIFICATION.md.
