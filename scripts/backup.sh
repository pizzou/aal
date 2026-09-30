#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER to the dedicated backup role}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${BACKUP_DIR:=/var/backups/aal}"
: "${RETENTION_DAYS:=14}"

mkdir -p "$BACKUP_DIR"
umask 077
ts=$(date -u +%Y%m%dT%H%M%SZ)
out="$BACKUP_DIR/aal-${ts}.dump"

echo "Creating PostgreSQL custom-format backup: $out"
pg_dump -Fc --no-owner --no-privileges --verbose "$PGDATABASE" > "$out"
pg_restore -l "$out" >/dev/null
sha256sum "$out" > "$out.sha256"

# Verify the archive is readable by pg_restore before exposing it as the latest backup.
pg_restore -l "$out" | grep -q 'TABLE DATA' || echo "Warning: archive contains no TABLE DATA entries (database may be empty)" >&2

test -s "$out"
find "$BACKUP_DIR" -name 'aal-*.dump' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -name 'aal-*.dump.sha256' -mtime +"$RETENTION_DAYS" -delete

echo "Backup verified: $out"
echo "To perform a real restore drill: RESTORE_DATABASE=<isolated_db> BACKUP_FILE=$out PGUSER=<admin> bash ./scripts/restore-drill.sh"
