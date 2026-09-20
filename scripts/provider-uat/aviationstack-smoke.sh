#!/usr/bin/env bash
set -euo pipefail

: "${AVIATIONSTACK_API_KEY:?Set AVIATIONSTACK_API_KEY}"
: "${AVIATIONSTACK_BASE_URL:=https://api.aviationstack.com/v1}"
: "${AVIATIONSTACK_TEST_FLIGHT:?Set AVIATIONSTACK_TEST_FLIGHT, e.g. KQ101}"

curl --fail-with-body --silent --show-error --max-time 30 \
  "${AVIATIONSTACK_BASE_URL%/}/flights?access_key=${AVIATIONSTACK_API_KEY}&flight_iata=${AVIATIONSTACK_TEST_FLIGHT}" \
  | jq -e '.data' >/dev/null

echo "Aviationstack UAT query passed."
