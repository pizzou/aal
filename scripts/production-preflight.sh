#!/usr/bin/env bash
set -euo pipefail

: "${BASE_URL:?Set BASE_URL, for example https://api.africalogisticaviation.com}"
: "${SECURITY_CORS_ALLOWED_ORIGINS:?Set SECURITY_CORS_ALLOWED_ORIGINS}"
: "${JWT_SECRET:?Set JWT_SECRET}"
: "${SPRING_DATASOURCE_USERNAME:?Set SPRING_DATASOURCE_USERNAME}"
: "${SPRING_DATASOURCE_PASSWORD:?Set SPRING_DATASOURCE_PASSWORD}"
: "${AAL_MAIL_FROM:?Set AAL_MAIL_FROM}"
: "${BREVO_API_KEY:?Set BREVO_API_KEY}"

case "$BASE_URL" in https://*) ;; *) echo "BASE_URL must use HTTPS" >&2; exit 1;; esac
case "$SECURITY_CORS_ALLOWED_ORIGINS" in *localhost*|*127.0.0.1*|'*') echo "CORS contains an unsafe origin" >&2; exit 1;; esac
[[ "$JWT_SECRET" != *CHANGE_ME* ]] || { echo "JWT_SECRET is a placeholder" >&2; exit 1; }
[[ ${#JWT_SECRET} -ge 32 ]] || { echo "JWT_SECRET must be at least 32 bytes" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_USERNAME" != "postgres" && "$SPRING_DATASOURCE_USERNAME" != "logi" ]] || { echo "Use a dedicated application DB role" >&2; exit 1; }
[[ "$SPRING_DATASOURCE_PASSWORD" != "CHANGE_ME" && -n "$SPRING_DATASOURCE_PASSWORD" ]] || { echo "SPRING_DATASOURCE_PASSWORD is not configured" >&2; exit 1; }
[[ "$AAL_MAIL_FROM" == *@* ]] || { echo "AAL_MAIL_FROM is invalid" >&2; exit 1; }
[[ "$BREVO_API_KEY" != "CHANGE_ME" && -n "$BREVO_API_KEY" ]] || { echo "BREVO_API_KEY is not configured" >&2; exit 1; }

curl --fail --silent --show-error --max-time 15 "$BASE_URL/actuator/health" >/dev/null
"$(dirname "$0")/verify-migrations.sh"
"$(dirname "$0")/verify-tls.sh"

echo "Production preflight passed."
