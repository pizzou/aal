#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL to the deployed AAL backend}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN for an AAL operator/UAT user}"
: "${SHIPMENT_ID:?Set SHIPMENT_ID to an existing UAT AIR shipment}"
: "${ORIGIN:?Set ORIGIN IATA code}"
: "${DESTINATION:?Set DESTINATION IATA code}"
: "${WEIGHT_KG:?Set WEIGHT_KG}"
: "${ALLOW_LIVE_BOOKING_UAT:?Set ALLOW_LIVE_BOOKING_UAT=true only for a provider-approved test booking}"
BASE_URL="${BASE_URL%/}"
[[ "$ALLOW_LIVE_BOOKING_UAT" == "true" ]] || { echo "Live booking UAT is disabled. Set ALLOW_LIVE_BOOKING_UAT=true for an explicitly approved test transaction." >&2; exit 1; }

search_payload="$(jq -cn --arg o "$ORIGIN" --arg d "$DESTINATION" --argjson w "$WEIGHT_KG" \
  '{origin:$o,destination:$d,weightKg:$w,from:(now|todateiso8601),to:((now+172800)|todateiso8601)}')"
search="$(curl --fail-with-body --silent --show-error --max-time 45 \
  -X POST "$BASE_URL/api/air-cargo/flights/search" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$search_payload")"

offer="$(jq -c '[.[] | select(.bookable == true and (.providerReference // "") != "")] | first // empty' <<<"$search")"
[[ -n "$offer" ]] || { echo "No provider-backed bookable offer was returned; refusing to create a live booking." >&2; exit 1; }

provider_reference="$(jq -r '.providerReference' <<<"$offer")"
provider_code="$(jq -r '.providerCode // empty' <<<"$offer")"
carrier_code="$(jq -r '.carrierCode' <<<"$offer")"
carrier_name="$(jq -r '.carrierName // .carrierCode' <<<"$offer")"
flight_number="$(jq -r '.flightNumber' <<<"$offer")"
departure="$(jq -r '.departure' <<<"$offer")"
arrival="$(jq -r '.arrival // empty' <<<"$offer")"
service_level="$(jq -r '.rateName // "STANDARD"' <<<"$offer")"
idempotency="AAL-UAT-BOOK-$(date -u +%Y%m%d%H%M%S)-$$"

payload="$(jq -cn \
  --arg sid "$SHIPMENT_ID" --arg cc "$carrier_code" --arg cn "$carrier_name" --arg fn "$flight_number" \
  --arg dep "$departure" --arg arr "$arrival" --arg o "$ORIGIN" --arg d "$DESTINATION" \
  --arg level "$service_level" --arg key "$idempotency" --arg pref "$provider_reference" --arg pc "$provider_code" \
  --argjson w "$WEIGHT_KG" \
  '{shipmentId:$sid,carrierCode:$cc,carrierName:$cn,flightNumber:$fn,departureTime:$dep,arrivalTime:(if $arr=="" then null else $arr end),originCode:$o,destinationCode:$d,weightKg:$w,serviceLevel:$level,idempotencyKey:$key,providerReference:$pref,providerCode:(if $pc=="" then null else $pc end)}')"

booking="$(curl --fail-with-body --silent --show-error --max-time 60 \
  -X POST "$BASE_URL/api/air-cargo/bookings" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$payload")"

echo "$booking" | jq .
booking_id="$(jq -er '.id' <<<"$booking")"
status="$(jq -r '.status' <<<"$booking")"
echo "Created provider booking id=$booking_id status=$status"

for i in $(seq 1 12); do
  current="$(curl --fail-with-body --silent --show-error --max-time 30 \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" "$BASE_URL/api/air-cargo/bookings/$booking_id")"
  state="$(jq -r '.status // "UNKNOWN"' <<<"$current")"
  echo "poll=$i status=$state"
  case "$state" in
    CONFIRMED) echo "$current" | jq .; exit 0;;
    CANCELLED|FAILED) echo "$current" | jq . >&2; echo "Provider booking reached a failure terminal state: $state" >&2; exit 1;;
    UNKNOWN|PENDING_PROVIDER|RECONCILING|CANCELLATION_PENDING) sleep 5;;
    *) sleep 3;;
  esac
done

echo "Booking UAT did not reach a terminal state within the polling window" >&2
exit 1
