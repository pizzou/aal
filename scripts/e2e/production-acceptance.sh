#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL, e.g. https://api.africalogisticaviation.com}"
: "${AAL_E2E_EMAIL:?Set AAL_E2E_EMAIL}"
: "${AAL_E2E_PASSWORD:?Set AAL_E2E_PASSWORD}"

base="${BASE_URL%/}"
COOKIE_JAR="${COOKIE_JAR:-$(mktemp)}"
trap 'rm -f "$COOKIE_JAR"' EXIT

json_post() {
  curl --fail-with-body --silent --show-error --max-time 30 \
    -H 'Content-Type: application/json' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "X-CSRF-Token: ${CSRF_TOKEN:-}" \
    -X POST "$1" -d "$2"
}

json_put() {
  curl --fail-with-body --silent --show-error --max-time 30 \
    -H 'Content-Type: application/json' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "X-CSRF-Token: ${CSRF_TOKEN:-}" \
    -X PUT "$1" -d "$2"
}

json_patch() {
  curl --fail-with-body --silent --show-error --max-time 30 \
    -H 'Content-Type: application/json' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "X-CSRF-Token: ${CSRF_TOKEN:-}" \
    -X PATCH "$1" -d "$2"
}

json_get() {
  curl --fail-with-body --silent --show-error --max-time 30 \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$1"
}

auth_login() {
  local payload="$1"
  curl --fail-with-body --silent --show-error --max-time 30 \
    -H 'Content-Type: application/json' \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "$base/api/auth/login" -d "$payload"
}

echo "[1/12] backend health"
curl --fail --silent --show-error --max-time 15 \
  "$base/actuator/health" | jq -e '.status == "UP"' >/dev/null

echo "[2/12] public quote request + result"
quote_payload="$(jq -cn \
  --arg email "$AAL_E2E_EMAIL" \
  '{origin:"Kigali, Rwanda",destination:"Nairobi, Kenya",serviceType:"AIR",commodity:"Medical supplies",chargeableWeightKg:25,volumeCbm:0.15,packages:2,company:"AAL E2E",contactName:"AAL E2E",email:$email,phone:"+250700000000",notes:"Automated staging acceptance"}')"
quote_json="$(curl --fail-with-body --silent --show-error --max-time 30 \
  -H 'Content-Type: application/json' \
  -X POST "$base/api/public/commercial/quotes" \
  -d "$quote_payload")"
request_token="$(jq -er '.requestToken' <<<"$quote_json")"
quote_result="$(curl --fail-with-body --silent --show-error --max-time 30 \
  "$base/api/public/commercial/quote-requests/$request_token")"
jq -e '.quoteReference and .origin and .destination' <<<"$quote_result" >/dev/null
mode="$(jq -r '.options[0].mode // "AIR"' <<<"$quote_result")"

echo "[3/12] public booking -> shipment -> tracking"
booking_reference="E2E-$(date -u +%Y%m%d%H%M%S)"
booking_payload="$(jq -cn \
  --arg mode "$mode" \
  --arg ref "$booking_reference" \
  --arg email "$AAL_E2E_EMAIL" \
  --arg token "$request_token" \
  '{origin:"Kigali, Rwanda",destination:"Nairobi, Kenya",serviceType:$mode,customerReference:$ref,company:"AAL E2E",contactName:"AAL E2E",email:$email,phone:"+250700000000",commodity:"Medical supplies",packages:2,quoteRequestToken:$token,selectedMode:$mode}')"
booking_json="$(curl --fail-with-body --silent --show-error --max-time 30 \
  -H 'Content-Type: application/json' \
  -X POST "$base/api/public/commercial/bookings" \
  -d "$booking_payload")"
shipment_id="$(jq -er '.shipmentId' <<<"$booking_json")"
tracking_token="$(jq -er '.trackingToken' <<<"$booking_json")"
jq -e '.status == "BOOKED"' <<<"$booking_json" >/dev/null
tracking="$(curl --fail-with-body --silent --show-error --max-time 30 \
  "$base/api/public/tracking/$tracking_token")"
jq -e '.trackingToken' <<<"$tracking" >/dev/null

echo "[4/12] authentication"
login_payload="$(jq -cn --arg email "$AAL_E2E_EMAIL" --arg password "$AAL_E2E_PASSWORD" \
  '{email:$email,password:$password}')"
login="$(auth_login "$login_payload")"
if [[ "$(jq -r '.otpRequired // false' <<<"$login")" == "true" ]]; then
  : "${AAL_E2E_OTP:?OTP is enabled. Set AAL_E2E_OTP for this run.}"
  challenge="$(jq -er '.otpChallengeToken' <<<"$login")"
  otp_payload="$(jq -cn --arg challenge "$challenge" '{otpChallengeToken:$challenge}')"
  curl --fail-with-body --silent --show-error --max-time 30 \
    -H 'Content-Type: application/json' -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "$base/api/auth/send-login-otp" -d "$otp_payload" >/dev/null
  login_payload="$(jq -cn \
    --arg email "$AAL_E2E_EMAIL" \
    --arg password "$AAL_E2E_PASSWORD" \
    --arg otp "$AAL_E2E_OTP" \
    --arg challenge "$challenge" \
    '{email:$email,password:$password,otp:$otp,otpChallengeToken:$challenge}')"
  login="$(auth_login "$login_payload")"
fi
session="$(json_get "$base/api/auth/session")"
jq -e '.userId and .role and .tenantId' <<<"$session" >/dev/null
CSRF_TOKEN="$(curl --fail --silent --show-error --max-time 15 \
  -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$base/api/auth/csrf" | jq -er '.token')"

echo "[5/12] users"
jq -e 'length >= 1' <<<"$(json_get "$base/api/users")" >/dev/null
jq -e 'length >= 1' <<<"$(json_get "$base/api/users/roles")" >/dev/null

echo "[6/12] settings"
settings_key="e2e.${AAL_E2E_SETTINGS_KEY:-acceptance}"
setting_payload="$(jq -cn --arg key "$settings_key" '{group:"e2e",key:$key,value:"ok"}')"
setting_json="$(json_put "$base/api/settings" "$setting_payload")"
jq -e --arg key "$settings_key" '.settingKey == $key and .settingValue == "ok"' <<<"$setting_json" >/dev/null
settings_json="$(json_get "$base/api/settings?group=e2e")"
jq -e --arg key "$settings_key" '[.[] | .settingKey == $key] | any' <<<"$settings_json" >/dev/null
encoded_key="$(printf '%s' "$settings_key" | jq -sRr @uri)"
curl --fail-with-body --silent --show-error --max-time 30 \
  -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-CSRF-Token: $CSRF_TOKEN" \
  -X DELETE "$base/api/settings?group=e2e&key=$encoded_key" >/dev/null

echo "[7/12] shipment documents + customs"
documents="$(json_get "$base/api/air-cargo/documents/shipment/$shipment_id")"
jq -e 'type == "array"' <<<"$documents" >/dev/null
customs_payload="$(jq -cn --arg shipment "$shipment_id" \
  '{shipmentId:$shipment,declarationType:"IMPORT",customsAuthority:"RRA",brokerName:"AAL E2E",hsCodes:"300490",countryOfOrigin:"RW",declaredValue:100,currency:"USD"}')"
customs_json="$(json_post "$base/api/air-cargo/documents/customs" "$customs_payload")"
jq -e 'type == "object"' <<<"$customs_json" >/dev/null

echo "[8/12] deliver shipment"
delivery_json="$(json_patch "$base/api/shipments/$shipment_id/status" '{"status":"DELIVERED"}')"
jq -e '.status == "DELIVERED"' <<<"$delivery_json" >/dev/null
json_get "$base/api/shipments/$shipment_id/events" | jq -e 'length >= 1' >/dev/null
json_get "$base/api/public/tracking/$tracking_token" | jq -e '.status == "DELIVERED"' >/dev/null

echo "[9/12] invoice"
invoice_no="AAL-E2E-$(date -u +%Y%m%d%H%M%S)"
due_date="$(date -u -d '+7 days' +%F 2>/dev/null || date -u -v+7d +%F)"
invoice_payload="$(jq -cn \
  --arg invoice "$invoice_no" \
  --arg issue "$(date -u +%F)" \
  --arg shipment "$shipment_id" \
  --arg due "$due_date" \
  '{invoiceNo:$invoice,issueDate:$issue,client:"AAL E2E",shipmentId:$shipment,currency:"USD",invoiceAmount:100,dueDate:$due,owner:"AAL E2E",notes:"Automated acceptance"}')"
invoice="$(json_post "$base/api/commercial/invoices" "$invoice_payload")"
invoice_id="$(jq -er '.id' <<<"$invoice")"

echo "[10/12] payment + idempotency"
payment_key="AAL-E2E-PAY-$(uuidgen 2>/dev/null || date -u +%Y%m%d%H%M%S)"
payment_body="$(jq -cn --arg key "$payment_key" '{amount:100,currency:"USD",idempotencyKey:$key,reference:"AAL E2E"}')"
payment1="$(json_post "$base/api/commercial/invoices/$invoice_id/payments" "$payment_body")"
payment2="$(json_post "$base/api/commercial/invoices/$invoice_id/payments" "$payment_body")"
jq -e '.amountPaid == 100' <<<"$payment1" >/dev/null
jq -e '.amountPaid == 100' <<<"$payment2" >/dev/null

echo "[11/12] reconciliation + audit"
reconciliation="$(json_get "$base/api/finance/reconciliation?currency=USD")"
jq -e 'type == "object"' <<<"$reconciliation" >/dev/null
audit="$(json_get "$base/api/audit?page=0&size=20")"
jq -e '.content' <<<"$audit" >/dev/null

echo "[12/12] final authenticated session"
json_get "$base/api/auth/session" | jq -e '.userId and .tenantId and .role' >/dev/null

echo "AAL full acceptance passed: quote -> email trigger -> result -> booking -> shipment -> tracking -> deliver -> invoice -> payment -> reconcile -> audit."
