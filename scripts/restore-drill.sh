#!/usr/bin/env bash
set -euo pipefail
: "${RESTORE_DATABASE:?RESTORE_DATABASE required}"; : "${BACKUP_FILE:?BACKUP_FILE required}"
createdb "$RESTORE_DATABASE" 2>/dev/null || true
pg_restore --clean --if-exists --no-owner --no-acl -d "$RESTORE_DATABASE" "$BACKUP_FILE"
psql -d "$RESTORE_DATABASE" -c 'SELECT version();'
psql -d "$RESTORE_DATABASE" -c 'SELECT count(*) FROM shipments;'
