#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?PGHOST required}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?PGDATABASE required}"
: "${PGUSER:?PGUSER required}"
: "${PGPASSWORD:?PGPASSWORD required}"
: "${BACKUP_DIR:?BACKUP_DIR required}"

mkdir -p "$BACKUP_DIR"
ts=$(date -u +%Y%m%dT%H%M%SZ)
out="$BACKUP_DIR/aal-base-${ts}.tar.gz"

pg_basebackup \
  --format=tar \
  --gzip \
  --wal-method=stream \
  --checkpoint=fast \
  --progress \
  --host="$PGHOST" \
  --port="$PGPORT" \
  --username="$PGUSER" \
  --pgdata="$out"

sha256sum "$out" > "$out.sha256"
find "$BACKUP_DIR" -name 'aal-base-*.tar.gz' -mtime +14 -delete
find "$BACKUP_DIR" -name 'aal-base-*.tar.gz.sha256' -mtime +14 -delete
