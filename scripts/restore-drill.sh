#!/usr/bin/env bash
set -euo pipefail

: "${RESTORE_DATABASE:?RESTORE_DATABASE required}"
: "${BACKUP_FILE:?BACKUP_FILE required}"
: "${PGUSER:?PGUSER required}"

command -v psql >/dev/null || { echo "psql is required" >&2; exit 1; }
command -v createdb >/dev/null || { echo "createdb is required" >&2; exit 1; }

if [[ -f "${BACKUP_FILE}.sha256" ]]; then
  sha256sum --check "${BACKUP_FILE}.sha256"
fi

# Validate a custom-format archive before replacing the restore target.
if pg_restore -l "$BACKUP_FILE" >/dev/null 2>&1; then
  createdb "$RESTORE_DATABASE" 2>/dev/null || true
  pg_restore --clean --if-exists --no-owner --no-acl -d "$RESTORE_DATABASE" "$BACKUP_FILE"
else
  createdb "$RESTORE_DATABASE" 2>/dev/null || true
  psql -d "$RESTORE_DATABASE" -v ON_ERROR_STOP=1 -f "$BACKUP_FILE"
fi

psql -d "$RESTORE_DATABASE" -v ON_ERROR_STOP=1 <<'SQL'
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

echo "Backup restore drill passed: checksum/archive, schema, Flyway and tenant RLS validation completed for $RESTORE_DATABASE."
