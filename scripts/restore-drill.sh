#!/usr/bin/env bash
set -euo pipefail

: "${RESTORE_DATABASE:?RESTORE_DATABASE required}"
: "${BACKUP_FILE:?BACKUP_FILE required}"
: "${PGUSER:?PGUSER required}"

createdb "$RESTORE_DATABASE" 2>/dev/null || true
pg_restore --clean --if-exists --no-owner --no-acl -d "$RESTORE_DATABASE" "$BACKUP_FILE"

psql -d "$RESTORE_DATABASE" -v ON_ERROR_STOP=1 <<'SQL'
SELECT current_database();
SELECT count(*) AS tenants FROM tenants;
SELECT count(*) AS users FROM users;
SELECT count(*) AS shipments FROM shipments;
SELECT count(*) AS invoices FROM commercial_invoices;
SELECT count(*) AS notification_queue FROM notification_queue;
SQL
