#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL}"
: "${QUOTE_TOKEN:?Set QUOTE_TOKEN to an unbooked public quote request token}"
: "${BOOKING_JSON:?Set BOOKING_JSON to the complete booking payload without quoteRequestToken}"

concurrency="${CONCURRENCY:-8}"
payload="$(jq -c --arg token "$QUOTE_TOKEN" '. + {quoteRequestToken:$token}' <<<"$BOOKING_JSON")"

pids=()
for i in $(seq 1 "$concurrency"); do
  curl --silent --show-error --max-time 30 \
    -o "/tmp/aal-booking-$i.json" \
    -w '%{http_code}\n' \
    -H 'Content-Type: application/json' \
    -X POST "$BASE_URL/api/public/commercial/bookings" \
    -d "$payload" > "/tmp/aal-booking-$i.status" &
  pids+=("$!")
done

for pid in "${pids[@]}"; do wait "$pid" || true; done

successes=$(grep -h '^201$\|^200$' /tmp/aal-booking-*.status | wc -l | tr -d ' ')
conflicts=$(grep -h '^409$' /tmp/aal-booking-*.status | wc -l | tr -d ' ')

cat /tmp/aal-booking-*.status
rm -f /tmp/aal-booking-*.status /tmp/aal-booking-*.json

[[ "$successes" -eq 1 ]] || { echo "Expected exactly one successful booking, got $successes" >&2; exit 1; }
[[ "$conflicts" -eq $((concurrency - 1)) ]] || { echo "Expected remaining requests to conflict" >&2; exit 1; }
echo "Concurrent booking guard passed."
