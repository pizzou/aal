#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN}"
: "${SHIPMENT_ID:?Set an existing UAT shipment ID}"
: "${UAT_EMAIL:?Set UAT_EMAIL}"
BASE_URL="${BASE_URL%/}"
event_type="PRODUCTION_UAT_$(date -u +%Y%m%dT%H%M%SZ)_$$"

payload="$(jq -cn --arg sid "$SHIPMENT_ID" --arg recipient "$UAT_EMAIL" --arg event "$event_type" \
  '{shipmentId:$sid,channel:"EMAIL",recipient:$recipient,eventType:$event,subject:"AAL notification pipeline UAT",body:"AAL operational notification pipeline UAT passed."}')"

curl --fail-with-body --silent --show-error --max-time 30 \
  -X POST "$BASE_URL/api/platform/notifications/queue" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$payload" >/dev/null

for i in $(seq 1 12); do
  rows="$(curl --fail-with-body --silent --show-error --max-time 30 \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" "$BASE_URL/api/platform/notifications/queue")"
  status="$(jq -r --arg sid "$SHIPMENT_ID" --arg recipient "$UAT_EMAIL" --arg event "$event_type" '[.[] | select((.shipment_id|tostring)==$sid and .recipient==$recipient and .event_type==$event)] | first | .status // "MISSING"' <<<"$rows")"
  echo "notification pipeline poll=$i status=$status"
  case "$status" in
    SENT) echo "AAL operational notification pipeline UAT passed."; exit 0;;
    FAILED) echo "$rows" | jq . >&2; echo "Notification queue reported FAILED" >&2; exit 1;;
    *) sleep 5;;
  esac
done

echo "Notification remained queued after the UAT polling window" >&2
exit 1
