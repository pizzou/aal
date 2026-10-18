#!/usr/bin/env bash
set -euo pipefail

: "${RESTORE_DATABASE:?RESTORE_DATABASE required (must be a NEW isolated database)}"
: "${BACKUP_FILE:?BACKUP_FILE required}"
: "${PGUSER:?PGUSER required}"

command -v psql >/dev/null || { echo "psql is required" >&2; exit 1; }
command -v createdb >/dev/null || { echo "createdb is required" >&2; exit 1; }
[[ -f "$BACKUP_FILE" && -s "$BACKUP_FILE" ]] || { echo "Backup file is missing or empty: $BACKUP_FILE" >&2; exit 1; }
[[ "$RESTORE_DATABASE" =~ ^[A-Za-z][A-Za-z0-9_]{0,62}$ ]] || {
  echo "RESTORE_DATABASE must be a simple PostgreSQL identifier (letters, digits, underscores; max 63 chars)." >&2
  exit 1
}
[[ "$RESTORE_DATABASE" != "postgres" && "$RESTORE_DATABASE" != "template0" && "$RESTORE_DATABASE" != "template1" ]] || {
  echo "Refusing to restore into a PostgreSQL system database." >&2
  exit 1
}
if [[ -n "${PGDATABASE:-}" && "$RESTORE_DATABASE" == "$PGDATABASE" ]]; then
  echo "Refusing to restore into PGDATABASE. Choose a separate isolated restore target." >&2
  exit 1
fi

if [[ -f "${BACKUP_FILE}.sha256" ]]; then
  (cd "$(dirname "$BACKUP_FILE")" && sha256sum --check "$(basename "${BACKUP_FILE}.sha256")")
fi

# A restore drill must never clean or overwrite an existing database. Require a
# new target so the script cannot accidentally destroy a live or previous test DB.
existing="$(psql -X -v ON_ERROR_STOP=1 -d postgres -Atqc \
  "SELECT 1 FROM pg_database WHERE datname = '$RESTORE_DATABASE'")"
if [[ "$existing" == "1" ]]; then
  echo "Refusing to overwrite existing database '$RESTORE_DATABASE'. Choose a fresh restore target and remove old drill databases manually after review." >&2
  exit 1
fi

createdb "$RESTORE_DATABASE"
cleanup_on_failure() {
  status=$?
  if (( status != 0 )); then
    echo "Restore drill failed. The isolated database '$RESTORE_DATABASE' was retained for diagnosis; inspect it before manually dropping it." >&2
  fi
  exit "$status"
}
trap cleanup_on_failure EXIT

if pg_restore -l "$BACKUP_FILE" >/dev/null 2>&1; then
  pg_restore --no-owner --no-acl -d "$RESTORE_DATABASE" "$BACKUP_FILE"
else
  psql -X -d "$RESTORE_DATABASE" -v ON_ERROR_STOP=1 -f "$BACKUP_FILE"
fi

psql -X -d "$RESTORE_DATABASE" -v ON_ERROR_STOP=1 <<'SQL'
SELECT current_database();
SELECT count(*) AS tenants FROM tenants;
SELECT count(*) AS users FROM users;
SELECT count(*) AS shipments FROM shipments;
SELECT count(*) AS invoices FROM commercial_invoices;
SELECT count(*) AS notification_queue FROM notification_queue;
SELECT count(*) AS applied_migrations FROM flyway_schema_history WHERE success = true;

DO $$
DECLARE missing_rls integer;
BEGIN
  SELECT count(*) INTO missing_rls
  FROM pg_class c
  JOIN pg_namespace n ON n.oid=c.relnamespace
  JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped
  WHERE n.nspname='public' AND c.relkind='r'
    AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity);
  IF missing_rls > 0 THEN
    RAISE EXCEPTION 'Restore drill found % tenant-owned tables without FORCE RLS', missing_rls;
  END IF;
END $$;
SQL

trap - EXIT
echo "Backup restore drill passed: checksum/archive, schema, Flyway and tenant RLS validation completed for isolated database '$RESTORE_DATABASE'."
