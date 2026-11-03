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
python3 ./scripts/release/check-production-config.py
# Validate Compose using real deployment environment only when that environment
# is provided. Never assume a successful static scan means containers are healthy.
if [[ -f "$ROOT/.env.production" ]]; then
  command -v docker >/dev/null || { echo "Docker required to validate .env.production" >&2; exit 1; }
  docker compose --env-file "$ROOT/.env.production" -f "$ROOT/docker-compose.production.yml" config --quiet
fi
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n

if [[ "$STATIC_ONLY" == true ]]; then
  echo "Static preflight passed; it does NOT approve production or verify database/services."
  exit 0
fi

: "${BASE_URL:?Set BASE_URL, for example https://aal-ocst.onrender.com}"
: "${JWT_SECRET:?Set JWT_SECRET}"
: "${SPRING_DATASOURCE_USERNAME:?Set SPRING_DATASOURCE_USERNAME}"
: "${SPRING_DATASOURCE_PASSWORD:?Set SPRING_DATASOURCE_PASSWORD}"
: "${SPRING_FLYWAY_USER:?Set SPRING_FLYWAY_USER for a dedicated migrator}"
: "${SPRING_FLYWAY_PASSWORD:?Set SPRING_FLYWAY_PASSWORD for the migrator}"
[[ "$SPRING_FLYWAY_USER" != "$SPRING_DATASOURCE_USERNAME" ]] || { echo "Runtime and migration DB roles must be different" >&2; exit 1; }
: "${AAL_BREVO_SENDER_EMAIL:?Set AAL_BREVO_SENDER_EMAIL to a verified AAL-domain sender}"
: "${BREVO_API_KEY:?Set BREVO_API_KEY}"

case "$BASE_URL" in https://*) ;; *) echo "BASE_URL must use HTTPS" >&2; exit 1;; esac
[[ "$JWT_SECRET" != *CHANGE_ME* ]] || { echo "JWT_SECRET is a placeholder" >&2; exit 1; }
[[ ${#JWT_SECRET} -ge 32 ]] || { echo "JWT_SECRET must be at least 32 bytes" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_USERNAME" != "postgres" && "$SPRING_DATASOURCE_USERNAME" != "logi" ]] || { echo "Use a dedicated application DB role" >&2; exit 1; }
[[ "$SPRING_FLYWAY_PASSWORD" != *CHANGE_ME* && -n "$SPRING_FLYWAY_PASSWORD" ]] || { echo "SPRING_FLYWAY_PASSWORD is not configured" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_PASSWORD" != *CHANGE_ME* && "$SPRING_DATASOURCE_PASSWORD" != *dev_pw* && -n "$SPRING_DATASOURCE_PASSWORD" ]] || { echo "SPRING_DATASOURCE_PASSWORD is not configured" >&2; exit 1; }
[[ "$BREVO_API_KEY" != "CHANGE_ME" && -n "$BREVO_API_KEY" ]] || { echo "BREVO_API_KEY is not configured" >&2; exit 1; }

sender_domain="${AAL_BREVO_SENDER_EMAIL##*@}"
[[ "${sender_domain,,}" == "${AAL_EMAIL_DOMAIN,,}" ]] || { echo "AAL_BREVO_SENDER_EMAIL must use $AAL_EMAIL_DOMAIN" >&2; exit 1; }

command -v jq >/dev/null || { echo "jq is required for health/provider response validation" >&2; exit 1; }
# A backend health endpoint alone is insufficient: the dedicated readiness
# probe checks DB privileges, Flyway, document archive, Brevo and ClamAV.
: "${APP_BACKEND_URL:?Set APP_BACKEND_URL to the HTTPS public backend URL}"
case "$APP_BACKEND_URL" in https://*) ;; *) echo "APP_BACKEND_URL must use HTTPS" >&2; exit 1;; esac
archive_mode="${FINANCIAL_DOCUMENT_STORAGE_PROVIDER:-DATABASE}"
case "$archive_mode" in
  DATABASE) : ;;
  SUPABASE)
    : "${SUPABASE_URL:?SUPABASE_URL is required for private invoice archive}"
    : "${SUPABASE_SERVICE_ROLE_KEY:?SUPABASE_SERVICE_ROLE_KEY is required for private invoice archive}"
    ;;
  *) echo "Unsupported financial document archive provider: $archive_mode" >&2; exit 1;;
esac
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
  # Admin-only readiness returns HTTP 503 on any blocking security / DB /
  # document-storage dependency. A green actuator health is insufficient.
  : "${RELEASE_ADMIN_ACCESS_TOKEN:?Set a short-lived ADMIN token for strict production readiness verification}"
  readiness_json="$(curl --fail-with-body --silent --show-error --max-time 25 \
    -H "Authorization: Bearer $RELEASE_ADMIN_ACCESS_TOKEN" \
    "$BASE_URL/api/production/readiness?strict=true")"
  jq -e '.ready == true and ([.checks[] | select(.blocking == true and .pass != true)] | length == 0)' \
    <<<"$readiness_json" >/dev/null || { echo "Strict production readiness failed" >&2; exit 1; }
  [[ -n "${BACKUP_FILE:-}" ]] || { echo "Full go-live requires BACKUP_FILE and a successful restore drill." >&2; exit 1; }
  [[ "$RUN_EXTERNAL_UAT" == "true" ]] || { echo "Full go-live requires RUN_EXTERNAL_UAT=true." >&2; exit 1; }
  echo "Full configured go-live preflight passed. This still does not replace independent penetration testing, business finance sign-off, or load testing."
else
  echo "Configured production preflight passed. WARNING: this is not a full go-live approval unless REQUIRE_FULL_GO_LIVE=true and every required external gate passed."
fi
