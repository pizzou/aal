#!/usr/bin/env bash
set -euo pipefail

# Runs inside the opt-in production backup service. The backup role has
# database read permissions and BYPASSRLS; credentials must be protected.
: "${PGHOST:?Set the PostgreSQL hostname}"
: "${PGDATABASE:?Set the database name}"
: "${PGUSER:?Set the backup role}"
: "${PGPASSWORD:?Set the backup role password}"
: "${BACKUP_DIR:=/backups}"
: "${RETENTION_DAYS:=14}"
mkdir -p "$BACKUP_DIR"
umask 077
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$BACKUP_DIR/aal-$stamp.dump"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT
pg_dump --format=custom --no-owner --no-privileges --file="$partial" "$PGDATABASE"
pg_restore --list "$partial" >/dev/null
test -s "$partial"
mv "$partial" "$target"
sha256sum "$target" > "$target.sha256"
# Only delete local copies that have outlived the operator-approved retention.
# Keep offsite S3 retention independent; never delete S3 backups here.
find "$BACKUP_DIR" -type f -name 'aal-*.dump' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -type f -name 'aal-*.dump.sha256' -mtime +"$RETENTION_DAYS" -delete
printf 'Verified backup available: %s\n' "$target"
