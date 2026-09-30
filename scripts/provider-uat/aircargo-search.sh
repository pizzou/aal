#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL to the deployed AAL backend}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN for an AAL operator/UAT user}"
PROVIDER_CODE="${PROVIDER_CODE:-CARGOAI}"
ORIGIN="${ORIGIN:-KGL}"
DESTINATION="${DESTINATION:-NBO}"
WEIGHT_KG="${WEIGHT_KG:-100}"
BASE_URL="${BASE_URL%/}"

# The verification endpoint is intentionally used for provider reachability; it never stores or books an offer.
payload="$(jq -cn --arg p "$PROVIDER_CODE" --arg o "$ORIGIN" --arg d "$DESTINATION" --argjson w "$WEIGHT_KG" \
  '{providerCode:$p,origin:$o,destination:$d,weightKg:$w,from:(now|todateiso8601),to:((now+86400)|todateiso8601)}')"

response="$(curl --fail-with-body --silent --show-error --max-time 45 \
  -X POST "$BASE_URL/api/air-cargo/integration/verify" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$payload")"

echo "$response" | jq .
status="$(jq -r '.status // "UNKNOWN"' <<<"$response")"
[[ "$status" == CONNECTED* ]] || {
  echo "Provider verification failed: status=$status" >&2
  exit 1
}

echo "AAL air-cargo provider search UAT passed for $PROVIDER_CODE $ORIGIN->$DESTINATION."
