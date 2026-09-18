#!/usr/bin/env bash
set -euo pipefail
: "${PGHOST:?PGHOST required}"; : "${PGPORT:=5432}"; : "${PGDATABASE:?PGDATABASE required}"; : "${PGUSER:?PGUSER required}"; : "${BACKUP_DIR:?BACKUP_DIR required}"
mkdir -p "$BACKUP_DIR"
export PGPASSWORD="${PGPASSWORD:?PGPASSWORD required}"
ts=$(date -u +%Y%m%dT%H%M%SZ)
pg_dump --format=custom --no-owner --no-acl "$PGDATABASE" > "$BACKUP_DIR/aal-$ts.dump"
find "$BACKUP_DIR" -type f -name 'aal-*.dump' -mtime +14 -delete
