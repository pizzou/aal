#!/usr/bin/env bash
set -euo pipefail

: "${BREVO_API_KEY:?Set BREVO_API_KEY}"
: "${AAL_MAIL_FROM:?Set AAL_MAIL_FROM}"
: "${UAT_EMAIL:?Set UAT_EMAIL}"

curl --fail-with-body --silent --show-error --max-time 30 \
  -X POST 'https://api.brevo.com/v3/smtp/email' \
  -H 'accept: application/json' \
  -H 'content-type: application/json' \
  -H "api-key: ${BREVO_API_KEY}" \
  -d '{"sender":{"email":"'"$AAL_MAIL_FROM"'","name":"Africa Logistic Aviation"},"to":[{"email":"'"$UAT_EMAIL"'"}],"subject":"AAL production UAT","htmlContent":"<p>AAL production email UAT passed.</p>"}' \
  | tee /tmp/aal-brevo-response.json

echo "Brevo UAT email accepted by provider."
