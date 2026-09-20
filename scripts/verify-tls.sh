#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL, for example https://api.africalogisticaviation.com}"
[[ "$BASE_URL" == https://* ]] || { echo "BASE_URL must use HTTPS" >&2; exit 1; }

host="${BASE_URL#https://}"
host="${host%%/*}"
port=443

openssl s_client -connect "${host}:${port}" -servername "$host" -verify_return_error </dev/null 2>/dev/null \
  | openssl x509 -noout -checkend $((30*24*3600)) -subject -issuer -dates

headers="$(curl --fail --silent --show-error --max-time 15 -I "$BASE_URL/actuator/health")"
printf '%s\n' "$headers" | grep -qi '^strict-transport-security:' || {
  echo "Strict-Transport-Security header missing" >&2
  exit 1
}

echo "TLS verification passed for $host"
