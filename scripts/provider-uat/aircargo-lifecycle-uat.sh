#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL to the deployed AAL backend}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN for an AAL operator/UAT user}"
: "${SHIPMENT_ID:?Set SHIPMENT_ID to an existing UAT AIR shipment}"
: "${ORIGIN:?Set ORIGIN IATA code}"
: "${DESTINATION:?Set DESTINATION IATA code}"
: "${WEIGHT_KG:?Set WEIGHT_KG}"
: "${ALLOW_LIVE_BOOKING_UAT:?Set ALLOW_LIVE_BOOKING_UAT=true only for provider-approved UAT}"
BASE_URL="${BASE_URL%/}"
[[ "$ALLOW_LIVE_BOOKING_UAT" == "true" ]] || { echo "Live air-cargo booking UAT disabled" >&2; exit 1; }

api() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl --fail-with-body --silent --show-error --max-time 60 \
      -X "$method" "$url" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer ${ACCESS_TOKEN}" -d "$body"
  else
    curl --fail-with-body --silent --show-error --max-time 60 \
      -X "$method" "$url" -H "Authorization: Bearer ${ACCESS_TOKEN}"
  fi
}

health="$(api GET "$BASE_URL/api/air-cargo/integration/health")"
search_payload="$(jq -cn --arg o "$ORIGIN" --arg d "$DESTINATION" --argjson w "$WEIGHT_KG" \
  '{origin:$o,destination:$d,weightKg:$w,from:(now|todateiso8601),to:((now+172800)|todateiso8601)}')"
search="$(api POST "$BASE_URL/api/air-cargo/flights/search" "$search_payload")"
offer="$(jq -c '[.[] | select(.bookable == true and (.providerReference // "") != "")] | first // empty' <<<"$search")"
[[ -n "$offer" ]] || { echo "No provider-backed bookable offer returned; no live booking was attempted" >&2; exit 1; }

provider="$(jq -r '.providerCode // empty' <<<"$offer")"
carrier_code="$(jq -r '.carrierCode' <<<"$offer")"
carrier_name="$(jq -r '.carrierName // .carrierCode' <<<"$offer")"
flight_number="$(jq -r '.flightNumber' <<<"$offer")"
departure="$(jq -r '.departure' <<<"$offer")"
arrival="$(jq -r '.arrival // empty' <<<"$offer")"
provider_reference="$(jq -r '.providerReference' <<<"$offer")"
service_level="$(jq -r '.rateName // "STANDARD"' <<<"$offer")"
booking_key="AAL-UAT-LIFECYCLE-$(date -u +%Y%m%d%H%M%S)-$$"

book="$(api POST "$BASE_URL/api/air-cargo/bookings" "$(jq -cn --arg sid "$SHIPMENT_ID" --arg cc "$carrier_code" --arg cn "$carrier_name" --arg fn "$flight_number" --arg dep "$departure" --arg arr "$arrival" --arg o "$ORIGIN" --arg d "$DESTINATION" --arg sl "$service_level" --arg key "$booking_key" --arg pref "$provider_reference" --arg p "$provider" --argjson w "$WEIGHT_KG" '{shipmentId:$sid,carrierCode:$cc,carrierName:$cn,flightNumber:$fn,departureTime:$dep,arrivalTime:(if $arr=="" then null else $arr end),originCode:$o,destinationCode:$d,weightKg:$w,serviceLevel:$sl,idempotencyKey:$key,providerReference:$pref,providerCode:(if $p=="" then null else $p end)}')")"
booking_id="$(jq -er '.id' <<<"$book")"

final=""
for i in $(seq 1 18); do
  current="$(api GET "$BASE_URL/api/air-cargo/bookings/$booking_id")"
  state="$(jq -r '.status // "UNKNOWN"' <<<"$current")"
  echo "booking=$booking_id poll=$i state=$state"
  case "$state" in
    CONFIRMED) final="$current"; break;;
    FAILED|CANCELLED) echo "$current" | jq . >&2; echo "Provider booking did not confirm" >&2; exit 1;;
    UNKNOWN|PENDING_PROVIDER|RECONCILING) sleep 5;;
    *) sleep 3;;
  esac
done
[[ -n "$final" ]] || { echo "Provider booking did not reach CONFIRMED within the UAT window" >&2; exit 1; }

echo "$final" | jq .

provider="$(jq -r '.provider // empty' <<<"$final")"
provider_health="$(jq -c --arg p "$provider" '[.providers[] | select(.code == $p)] | first // {}' <<<"$health")"
can_amend="$(jq -r '.amendment // false' <<<"$provider_health")"
can_cancel="$(jq -r '.cancellation // false' <<<"$provider_health")"

if [[ "$can_amend" == "true" && "${UAT_AMEND:-false}" == "true" ]]; then
  amend_key="AAL-UAT-AMEND-$(date -u +%Y%m%d%H%M%S)-$$"
  amend="$(api PATCH "$BASE_URL/api/air-cargo/bookings/$booking_id" "$(jq -cn --arg key "$amend_key" --arg fn "$flight_number" --argjson w "$WEIGHT_KG" '{idempotencyKey:$key,flightNumber:$fn,weightKg:$w}')")"
  echo "$amend" | jq .
  jq -e '.status' <<<"$amend" >/dev/null
  echo "Amendment UAT passed for provider=$provider"
elif [[ "$can_amend" == "true" ]]; then
  echo "Provider supports amendment; UAT_AMEND=true was not requested, so no live mutation was performed."
else
  echo "Provider reports amendment unsupported; no amendment request was sent."
fi

if [[ "$can_cancel" == "true" && "${UAT_CANCEL:-false}" == "true" ]]; then
  cancel_key="AAL-UAT-CANCEL-$(date -u +%Y%m%d%H%M%S)-$$"
  cancel="$(api POST "$BASE_URL/api/air-cargo/bookings/$booking_id/cancel" "$(jq -cn --arg key "$cancel_key" '{idempotencyKey:$key,reason:"AAL approved provider UAT cancellation"}')")"
  echo "$cancel" | jq .
  jq -e '.status' <<<"$cancel" >/dev/null
  echo "Cancellation UAT passed for provider=$provider"
elif [[ "$can_cancel" == "true" ]]; then
  echo "Provider supports cancellation; UAT_CANCEL=true was not requested, so no live mutation was performed."
else
  echo "Provider reports cancellation unsupported; no cancellation request was sent."
fi

echo "Air-cargo booking UAT passed through provider confirmation."
