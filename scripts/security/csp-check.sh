#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--static" ]]; then
  grep -R "script-src .*https:" frontend/middleware.ts frontend/next.config.js >/dev/null 2>&1 && {
    echo "CSP script-src must not contain a wildcard https: source" >&2
    exit 1
  } || true
  grep -R "connect-src .*https:" frontend/middleware.ts >/dev/null 2>&1 && {
    echo "CSP connect-src must be explicitly scoped to the AAL API origin" >&2
    exit 1
  } || true
  echo "Static CSP checks passed."
  exit 0
fi

: "${FRONTEND_URL:?Set FRONTEND_URL}"
FRONTEND_URL="${FRONTEND_URL%/}"
headers="$(mktemp)"
body="$(mktemp)"
trap 'rm -f "$headers" "$body"' EXIT

curl --fail-with-body --silent --show-error --max-time 20 \
  -D "$headers" -o "$body" "$FRONTEND_URL/"

get_header() {
  awk -F': ' -v key="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" \
    'tolower($1)==key {gsub("\r","",$2); print $2}' "$headers" | tail -1
}

csp="$(get_header content-security-policy)"
[[ -n "$csp" ]] || { echo "Missing Content-Security-Policy" >&2; exit 1; }
grep -q "default-src 'self'" <<<"$csp" || { echo "CSP default-src is missing" >&2; exit 1; }
grep -q "object-src 'none'" <<<"$csp" || { echo "CSP object-src is not locked down" >&2; exit 1; }
grep -q "frame-ancestors 'none'" <<<"$csp" || { echo "CSP frame-ancestors is not locked down" >&2; exit 1; }
grep -q "script-src .*'nonce-" <<<"$csp" || { echo "CSP is missing a per-response script nonce" >&2; exit 1; }
grep -q "strict-dynamic" <<<"$csp" || { echo "CSP script-src is missing strict-dynamic" >&2; exit 1; }
if grep -Eq '(^|[; ])connect-src[^;]*(^|[[:space:]])https:[[:space:]]*(;|$)' <<<"$csp"; then
  echo "CSP connect-src is overly broad" >&2
  exit 1
fi

echo "Production CSP passed for $FRONTEND_URL"
