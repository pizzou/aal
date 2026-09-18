#!/usr/bin/env bash
set -euo pipefail

# Backup approach verified directly (see RLS_VERIFICATION.md): pg_dump -Fc (custom
# format) followed by pg_restore was tested end-to-end against this exact schema,
# including confirming all 6 RLS policies survive the restore intact — a real gotcha
# some backup approaches miss. Row counts and policies were byte-for-byte identical
# after a full dump -> restore-into-new-database -> compare cycle.
#
# IMPORTANT, found by actually running this: pg_dump run as the tenant-scoped
# `logi_app` role FAILS on every RLS-protected table, because pg_dump isn't scoped
# to any single tenant and FORCE ROW LEVEL SECURITY correctly blocks it. Backups
# must run as a role with BYPASSRLS — NOT the app's normal role, and NOT a full
# superuser either (least privilege: BYPASSRLS + SELECT is enough). See
# scripts/create_backup_role.sql.

: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER, must be a role with BYPASSRLS — see scripts/create_backup_role.sql}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${BACKUP_DIR:=/var/backups/logiplatform}"
: "${RETENTION_DAYS:=14}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_FILE="$BACKUP_DIR/logiplatform_${TIMESTAMP}.dump"

echo "[$(date -u)] Starting backup to $BACKUP_FILE"
pg_dump -Fc --no-owner --no-privileges "$PGDATABASE" > "$BACKUP_FILE"

# Verify the dump is structurally valid before trusting it — a truncated or corrupt
# dump file that "succeeds" silently is worse than a loud failure.
if ! pg_restore -l "$BACKUP_FILE" > /dev/null 2>&1; then
    echo "[$(date -u)] ERROR: backup file failed integrity check, deleting and failing loudly"
    rm -f "$BACKUP_FILE"
    exit 1
fi

echo "[$(date -u)] Backup verified OK: $(du -h "$BACKUP_FILE" | cut -f1)"

# Prune old backups
find "$BACKUP_DIR" -name "logiplatform_*.dump" -mtime "+${RETENTION_DAYS}" -delete
echo "[$(date -u)] Pruned backups older than ${RETENTION_DAYS} days"

# IMPORTANT — this script alone is NOT a backup strategy:
#   1. It writes to local disk. Ship BACKUP_DIR to off-instance storage (S3/GCS/etc.)
#      as a separate step, or this protects against nothing if the instance itself is lost.
#   2. It's never been scheduled or run outside this manual test. Wire it to cron or
#      a systemd timer, and — critically — actually test a restore periodically,
#      not just confirm the backup command exits 0.
#   3. Point-in-time recovery (WAL archiving) is not covered here at all — this is a
#      once-daily-snapshot approach, meaning up to 24h of data loss in the worst case.
#      A production financial/logistics system with real client data typically wants
#      WAL-based continuous archiving (e.g. via pgBackRest or WAL-G), not just this.
