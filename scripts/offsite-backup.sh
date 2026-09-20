#!/usr/bin/env bash
set -euo pipefail

: "${BACKUP_DIR:?Set BACKUP_DIR}"
: "${BACKUP_S3_BUCKET:?Set BACKUP_S3_BUCKET}"
: "${AWS_REGION:=af-south-1}"

aws s3 sync "$BACKUP_DIR" "s3://${BACKUP_S3_BUCKET}/backups/" \
  --region "$AWS_REGION" \
  --sse AES256 \
  --only-show-errors
