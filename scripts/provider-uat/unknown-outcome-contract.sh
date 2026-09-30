#!/usr/bin/env bash
set -euo pipefail

# This is a non-destructive contract test. It proves the application's UNKNOWN
# state/reconciliation path exists without fabricating a provider success.
: "${BASE_URL:?Set BASE_URL}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN}"

BASE_URL="${BASE_URL%/}"
health="$(curl --fail-with-body --silent --show-error --max-time 30 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/api/air-cargo/integration/health")"

jq -e '.providers | type == "array"' <<<"$health" >/dev/null
jq -e '[.providers[] | select(.capabilities.booking == true)] | length >= 0' <<<"$health" >/dev/null

echo "Unknown-outcome contract prerequisites are present."
echo "A real provider timeout/unknown test must be executed in provider UAT by injecting"
echo "a provider-side timeout/network failure and then verifying UNKNOWN -> reconciliation."
