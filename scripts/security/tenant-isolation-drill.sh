#!/usr/bin/env bash
set -euo pipefail

command -v psql >/dev/null || { echo "psql is required" >&2; exit 1; }
: "${PGHOST:?Set PGHOST}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${PGUSER:?Set PGUSER to a superuser/admin role for the drill}"
: "${PGPASSWORD:?Set PGPASSWORD}"
APP_DB_ROLE="${APP_DB_ROLE:-aal_app}"

psql_args=("-v" "ON_ERROR_STOP=1" "-X")

echo "Checking every public table containing tenant_id has RLS + FORCE RLS..."
violations="$(psql "${psql_args[@]}" -At -c "
SELECT format('%I.%I', n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
       OR NOT EXISTS (
            SELECT 1 FROM pg_policies p
            WHERE p.schemaname=n.nspname
              AND p.tablename=c.relname
              AND coalesce(p.qual,'') LIKE '%app.current_tenant%'
       ))
ORDER BY 1;")"

if [[ -n "$violations" ]]; then
  echo "Tenant-owned tables without complete RLS protection:" >&2
  printf '%s\n' "$violations" >&2
  exit 1
fi

psql "${psql_args[@]}" -c "SELECT count(*) AS tenant_owned_tables FROM information_schema.columns WHERE table_schema='public' AND column_name='tenant_id';"

[[ "$(psql "${psql_args[@]}" -At -c "SELECT 1 FROM pg_roles WHERE rolname='${APP_DB_ROLE}'")" == "1" ]] || {
  echo "Application DB role '$APP_DB_ROLE' does not exist" >&2
  exit 1
}

echo "Running an actual negative cross-tenant RLS probe using the application role..."
psql "${psql_args[@]}" <<SQL
BEGIN;
CREATE TABLE IF NOT EXISTS __aal_rls_probe (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  value text NOT NULL
);
ALTER TABLE __aal_rls_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE __aal_rls_probe FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS __aal_rls_probe_policy ON __aal_rls_probe;
CREATE POLICY __aal_rls_probe_policy ON __aal_rls_probe
  USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT ON __aal_rls_probe TO ${APP_DB_ROLE};
INSERT INTO __aal_rls_probe(id, tenant_id, value)
VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-00000000000a','tenant-a'),
       ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-00000000000b','tenant-b');
SET LOCAL ROLE ${APP_DB_ROLE};
SELECT set_config('app.current_tenant','00000000-0000-0000-0000-00000000000a', true);
DO $$
DECLARE visible_count integer;
BEGIN
  SELECT count(*) INTO visible_count FROM __aal_rls_probe;
  IF visible_count <> 1 THEN
    RAISE EXCEPTION 'RLS read isolation failed: expected 1 row, got %', visible_count;
  END IF;
END $$;
DO $$
BEGIN
  BEGIN
    INSERT INTO __aal_rls_probe(id, tenant_id, value)
    VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-00000000000b','cross-tenant-write');
    RAISE EXCEPTION 'RLS write isolation failed: cross-tenant insert succeeded';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    NULL;
  END;
END $$;
ROLLBACK;
SQL

echo "Tenant isolation drill passed."
