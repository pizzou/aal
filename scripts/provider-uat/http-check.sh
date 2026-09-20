#!/usr/bin/env bash
set -euo pipefail

: "${PROVIDER_NAME:?Set PROVIDER_NAME}"
: "${PROVIDER_URL:?Set PROVIDER_URL}"

curl --fail --silent --show-error --max-time 20 \
  -H "Authorization: Bearer ${PROVIDER_API_KEY:-}" \
  "$PROVIDER_URL" >/dev/null

echo "$PROVIDER_NAME UAT endpoint reachable."
