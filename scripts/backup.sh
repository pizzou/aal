#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER to the dedicated backup role}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${BACKUP_DIR:=/var/backups/aal}"
: "${RETENTION_DAYS:=14}"

mkdir -p "$BACKUP_DIR"
ts=$(date -u +%Y%m%dT%H%M%SZ)
out="$BACKUP_DIR/aal-${ts}.dump"

pg_dump -Fc --no-owner --no-privileges "$PGDATABASE" > "$out"
pg_restore -l "$out" >/dev/null
sha256sum "$out" > "$out.sha256"

find "$BACKUP_DIR" -name 'aal-*.dump' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -name 'aal-*.dump.sha256' -mtime +"$RETENTION_DAYS" -delete

echo "Backup verified: $out"
