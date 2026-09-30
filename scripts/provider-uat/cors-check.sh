#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL to the deployed backend, e.g. https://aal-ocst.onrender.com}"
FRONTEND_URL="${FRONTEND_URL:-https://aal-a.vercel.app}"
BASE_URL="${BASE_URL%/}"

case "$BASE_URL" in https://*) ;; *) echo "BASE_URL must use HTTPS" >&2; exit 1;; esac
case "$FRONTEND_URL" in https://*) ;; *) echo "FRONTEND_URL must use HTTPS" >&2; exit 1;; esac

headers="$(mktemp)"
trap 'rm -f "$headers"' EXIT

curl --fail-with-body --silent --show-error --max-time 20 \
  -D "$headers" -o /dev/null \
  -X OPTIONS "$BASE_URL/api/platform/health" \
  -H "Origin: $FRONTEND_URL" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization,content-type,cache-control,x-csrf-token'

get_header() { awk -F': ' -v key="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" 'tolower($1)==key {gsub("\r", "", $2); print $2}' "$headers" | tail -1; }

allow_origin="$(get_header access-control-allow-origin)"
allow_methods="$(get_header access-control-allow-methods)"
allow_headers="$(get_header access-control-allow-headers)"
allow_credentials="$(get_header access-control-allow-credentials)"

[[ "$allow_origin" == "$FRONTEND_URL" ]] || { echo "CORS origin mismatch: '$allow_origin'" >&2; exit 1; }
[[ "$allow_credentials" == "true" ]] || { echo "CORS credentials must be true for the AAL cookie/JWT flow" >&2; exit 1; }
for required in GET OPTIONS; do
  grep -qi "\b${required}\b" <<<"$allow_methods" || { echo "CORS methods omit $required" >&2; exit 1; }
done
for required in authorization content-type cache-control x-csrf-token; do
  grep -qi "\b${required}\b" <<<"$allow_headers" || { echo "CORS headers omit $required" >&2; exit 1; }
done

echo "Production CORS preflight passed for Origin=$FRONTEND_URL" 
echo "Access-Control-Allow-Origin: $allow_origin" 
echo "Access-Control-Allow-Credentials: $allow_credentials"
