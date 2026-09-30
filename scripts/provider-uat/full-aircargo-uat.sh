#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL}"
: "${ACCESS_TOKEN:?Set ACCESS_TOKEN}"
: "${SHIPMENT_ID:?Set an existing UAT AIR shipment}"
: "${ORIGIN:?Set origin IATA code}"
: "${DESTINATION:?Set destination IATA code}"
: "${WEIGHT_KG:?Set chargeable weight}"
: "${ALLOW_LIVE_BOOKING_UAT:?Set ALLOW_LIVE_BOOKING_UAT=true for a provider-approved test booking}"
: "${PROVIDER_CODE:=CARGOAI}"
BASE_URL="${BASE_URL%/}"
[[ "$ALLOW_LIVE_BOOKING_UAT" == "true" ]] || { echo "Live booking UAT is disabled" >&2; exit 1; }

api() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl --fail-with-body --silent --show-error --max-time 60 -X "$method" "$url" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $ACCESS_TOKEN" -d "$body"
  else
    curl --fail-with-body --silent --show-error --max-time 60 -X "$method" "$url" \
      -H "Authorization: Bearer $ACCESS_TOKEN"
  fi
}

echo "1/8 Provider health"
api GET "$BASE_URL/api/air-cargo/integration/health" | jq .

echo "2/8 Live search"
search="$(api POST "$BASE_URL/api/air-cargo/flights/search" "$(jq -cn \
  --arg o "$ORIGIN" --arg d "$DESTINATION" --argjson w "$WEIGHT_KG" \
  '{origin:$o,destination:$d,weightKg:$w,from:(now|todateiso8601),to:((now+172800)|todateiso8601)}')")"
offer="$(jq -c '[.[] | select(.bookable == true and (.providerReference // "") != "")] | first // empty' <<<"$search")"
[[ -n "$offer" ]] || { echo "No live bookable offer returned; refusing live booking." >&2; exit 1; }
echo "$offer" | jq .

provider="$(jq -r '.providerCode // empty' <<<"$offer")"
carrier_code="$(jq -r '.carrierCode' <<<"$offer")"
carrier_name="$(jq -r '.carrierName // .carrierCode' <<<"$offer")"
flight_number="$(jq -r '.flightNumber' <<<"$offer")"
departure="$(jq -r '.departure' <<<"$offer")"
arrival="$(jq -r '.arrival // empty' <<<"$offer")"
provider_reference="$(jq -r '.providerReference' <<<"$offer")"
service_level="$(jq -r '.rateName // "STANDARD"' <<<"$offer")"
booking_key="AAL-UAT-FULL-$(date -u +%Y%m%d%H%M%S)-$$"

echo "3/8 Booking"
book="$(api POST "$BASE_URL/api/air-cargo/bookings" "$(jq -cn \
  --arg sid "$SHIPMENT_ID" --arg cc "$carrier_code" --arg cn "$carrier_name" \
  --arg fn "$flight_number" --arg dep "$departure" --arg arr "$arrival" \
  --arg o "$ORIGIN" --arg d "$DESTINATION" --arg sl "$service_level" \
  --arg key "$booking_key" --arg pref "$provider_reference" --arg p "$provider" \
  --argjson w "$WEIGHT_KG" \
  '{shipmentId:$sid,carrierCode:$cc,carrierName:$cn,flightNumber:$fn,departureTime:$dep,
    arrivalTime:(if $arr=="" then null else $arr end),originCode:$o,destinationCode:$d,
    weightKg:$w,serviceLevel:$sl,idempotencyKey:$key,providerReference:$pref,
    providerCode:(if $p=="" then null else $p end)}')")"
booking_id="$(jq -er '.id' <<<"$book")"
echo "$book" | jq .

echo "4/8 Confirmation/reconciliation"
confirmed=false
for i in $(seq 1 18); do
  current="$(api GET "$BASE_URL/api/air-cargo/bookings/$booking_id")"
  state="$(jq -r '.status // "UNKNOWN"' <<<"$current")"
  echo "poll=$i state=$state"
  case "$state" in
    CONFIRMED) confirmed=true; final="$current"; break;;
    FAILED|CANCELLED) echo "$current" | jq . >&2; exit 1;;
    UNKNOWN|PENDING_PROVIDER|RECONCILING) sleep 5;;
    *) sleep 3;;
  esac
done
[[ "$confirmed" == true ]] || { echo "Booking did not confirm/reconcile in UAT window" >&2; exit 1; }
echo "$final" | jq .
provider_reference_final="$(jq -r '.providerReference // empty' <<<"$final")"

echo "5/8 Flight tracking"
track="$(api GET "$BASE_URL/api/air-cargo/integration/track?providerCode=$(jq -nr --arg p "$provider" '$p|@uri')&providerReference=$(jq -nr --arg p "$provider_reference_final" '$p|@uri')" 2>/dev/null || true)"
if [[ -n "$track" ]]; then
  echo "$track" | jq .
else
  echo "Provider-reference flight tracking endpoint is not exposed on this deployment; verify through the configured tracking adapter."
fi

echo "6/8 AWB/e-AWB readiness"
awb="$(jq -r '.confirmationNumber // .awbNumber // empty' <<<"$final")"
if [[ -z "$awb" ]]; then
  echo "No AWB returned by provider; the AAL AWB workflow must assign/create the AWB before acceptance."
else
  echo "Provider returned AWB/confirmation reference: $awb"
  awb_payload="$(jq -cn --arg sid "$SHIPMENT_ID" --arg awb "$awb" --arg o "$ORIGIN" --arg d "$DESTINATION" --argjson w "$WEIGHT_KG"     '{shipmentId:$sid,awbNumber:$awb,awbType:"MAWB",shipperName:"AAL UAT",consigneeName:"AAL UAT Consignee",
      originAirport:$o,destinationAirport:$d,pieces:1,grossWeightKg:$w,chargeableWeightKg:$w,commodity:"GENERAL CARGO",
      dangerousGoods:false}')"
  awb_record="$(api POST "$BASE_URL/api/air-cargo/documents/awb" "$awb_payload")"
  echo "$awb_record" | jq .
  if [[ "${UAT_EAWB_SUBMIT:-false}" == "true" ]]; then
    api POST "$BASE_URL/api/air-cargo/documents/awb/$(jq -er '.id' <<<"$awb_record")/submit-to-carrier" | jq .
  fi
fi

echo "7/8 Amendment/cancellation capability"
health="$(api GET "$BASE_URL/api/air-cargo/integration/health")"
caps="$(jq -c --arg p "$provider" '[.providers[] | select(.code==$p)] | first // {}' <<<"$health")"
echo "$caps" | jq .
if [[ "${UAT_AMEND:-false}" == "true" && "$(jq -r '.amendment // false' <<<"$caps")" == "true" ]]; then
  api PATCH "$BASE_URL/api/air-cargo/bookings/$booking_id" "$(jq -cn --arg key "AAL-UAT-AMEND-$(date -u +%s)-$$" --arg fn "$flight_number" --argjson w "$WEIGHT_KG" '{idempotencyKey:$key,flightNumber:$fn,weightKg:$w}')" | jq .
fi
if [[ "${UAT_CANCEL:-false}" == "true" && "$(jq -r '.cancellation // false' <<<"$caps")" == "true" ]]; then
  api POST "$BASE_URL/api/air-cargo/bookings/$booking_id/cancel" '{"idempotencyKey":"AAL-UAT-CANCEL-'$(date -u +%s)-$$'","reason":"AAL approved provider UAT cancellation"}' | jq .
fi

echo "8/8 Operational notification pipeline"
if [[ -n "${UAT_EMAIL:-}" ]]; then
  SHIPMENT_ID="$SHIPMENT_ID" UAT_EMAIL="$UAT_EMAIL" \
    BASE_URL="$BASE_URL" ACCESS_TOKEN="$ACCESS_TOKEN" \
    bash "$(dirname "$0")/notification-pipeline-smoke.sh"
else
  echo "UAT_EMAIL not set; notification mutation was not attempted."
fi

echo "Full air-cargo UAT completed."
