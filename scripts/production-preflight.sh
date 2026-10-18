#!/usr/bin/env bash
set -euo pipefail

STATIC_ONLY=false
if [[ "${1:-}" == "--static-only" ]]; then STATIC_ONLY=true; fi
REQUIRE_FULL_GO_LIVE="${REQUIRE_FULL_GO_LIVE:-false}"
RUN_EXTERNAL_UAT="${RUN_EXTERNAL_UAT:-false}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${SECURITY_CORS_ALLOWED_ORIGINS:=https://aal-a.vercel.app}"
: "${APP_FRONTEND_URL:=https://aal-a.vercel.app}"
: "${AAL_EMAIL_DOMAIN:=africalogisticaviation.com}"

case "$APP_FRONTEND_URL" in https://*) ;; *) echo "APP_FRONTEND_URL must use HTTPS" >&2; exit 1;; esac
case "$SECURITY_CORS_ALLOWED_ORIGINS" in *localhost*|*127.0.0.1*|'*'|*http://*) echo "CORS contains an unsafe origin" >&2; exit 1;; esac
[[ "$SECURITY_CORS_ALLOWED_ORIGINS" == *"$APP_FRONTEND_URL"* ]] || { echo "APP_FRONTEND_URL is not present in SECURITY_CORS_ALLOWED_ORIGINS" >&2; exit 1; }

bash ./scripts/verify-migrations.sh
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n

if [[ "$STATIC_ONLY" == true ]]; then
  echo "Static production preflight passed."
  exit 0
fi

: "${BASE_URL:?Set BASE_URL, for example https://aal-ocst.onrender.com}"
: "${JWT_SECRET:?Set JWT_SECRET}"
: "${SPRING_DATASOURCE_USERNAME:?Set SPRING_DATASOURCE_USERNAME}"
: "${SPRING_DATASOURCE_PASSWORD:?Set SPRING_DATASOURCE_PASSWORD}"
: "${AAL_BREVO_SENDER_EMAIL:?Set AAL_BREVO_SENDER_EMAIL to a verified AAL-domain sender}"
: "${BREVO_API_KEY:?Set BREVO_API_KEY}"

case "$BASE_URL" in https://*) ;; *) echo "BASE_URL must use HTTPS" >&2; exit 1;; esac
[[ "$JWT_SECRET" != *CHANGE_ME* ]] || { echo "JWT_SECRET is a placeholder" >&2; exit 1; }
[[ ${#JWT_SECRET} -ge 32 ]] || { echo "JWT_SECRET must be at least 32 bytes" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_USERNAME" != "postgres" && "$SPRING_DATASOURCE_USERNAME" != "logi" ]] || { echo "Use a dedicated application DB role" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_PASSWORD" != *CHANGE_ME* && "$SPRING_DATASOURCE_PASSWORD" != *dev_pw* && -n "$SPRING_DATASOURCE_PASSWORD" ]] || { echo "SPRING_DATASOURCE_PASSWORD is not configured" >&2; exit 1; }
[[ "$BREVO_API_KEY" != "CHANGE_ME" && -n "$BREVO_API_KEY" ]] || { echo "BREVO_API_KEY is not configured" >&2; exit 1; }

sender_domain="${AAL_BREVO_SENDER_EMAIL##*@}"
[[ "${sender_domain,,}" == "${AAL_EMAIL_DOMAIN,,}" ]] || { echo "AAL_BREVO_SENDER_EMAIL must use $AAL_EMAIL_DOMAIN" >&2; exit 1; }

command -v jq >/dev/null || { echo "jq is required for health/provider response validation" >&2; exit 1; }
health_json="$(curl --fail --silent --show-error --max-time 20 "$BASE_URL/actuator/health")"
health_status="$(jq -er '.status' <<<"$health_json")"
[[ "$health_status" == "UP" ]] || { echo "Application health is '$health_status', expected UP" >&2; exit 1; }
APP_DB_ROLE="$SPRING_DATASOURCE_USERNAME" bash ./scripts/security/tenant-isolation-drill.sh
bash ./scripts/provider-uat/cors-check.sh
bash ./scripts/verify-tls.sh

if [[ "${BACKUP_FILE:-}" != "" ]]; then
  : "${RESTORE_DATABASE:?Set RESTORE_DATABASE to a fresh isolated database for the restore drill}"
  bash ./scripts/restore-drill.sh
else
  echo "WARNING: restore drill skipped; BACKUP_FILE was not supplied." >&2
fi

if [[ "$RUN_EXTERNAL_UAT" == "true" ]]; then
  bash ./scripts/provider-uat/brevo-delivery.sh
  bash ./scripts/provider-uat/aviationstack-smoke.sh
  bash ./scripts/provider-uat/aircargo-search.sh
else
  echo "WARNING: external provider UAT skipped; RUN_EXTERNAL_UAT is not true." >&2
fi

if [[ "$REQUIRE_FULL_GO_LIVE" == "true" ]]; then
  [[ -n "${BACKUP_FILE:-}" ]] || { echo "Full go-live requires BACKUP_FILE and a successful restore drill." >&2; exit 1; }
  [[ "$RUN_EXTERNAL_UAT" == "true" ]] || { echo "Full go-live requires RUN_EXTERNAL_UAT=true." >&2; exit 1; }
  echo "Full configured go-live preflight passed. This still does not replace independent penetration testing, business finance sign-off, or load testing."
else
  echo "Configured production preflight passed. WARNING: this is not a full go-live approval unless REQUIRE_FULL_GO_LIVE=true and every required external gate passed."
fi
