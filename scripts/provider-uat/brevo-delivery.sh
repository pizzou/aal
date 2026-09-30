#!/usr/bin/env bash
set -euo pipefail

: "${BREVO_API_KEY:?Set BREVO_API_KEY}"
: "${AAL_BREVO_SENDER_EMAIL:?Set AAL_BREVO_SENDER_EMAIL to a verified AAL-domain sender}"
: "${UAT_EMAIL:?Set UAT_EMAIL to an inbox you control}"
AAL_EMAIL_DOMAIN="${AAL_EMAIL_DOMAIN:-africalogisticaviation.com}"
BREVO_API_URL="${BREVO_API_URL:-https://api.brevo.com/v3/smtp/email}"
BREVO_SENDERS_API_URL="${BREVO_SENDERS_API_URL:-https://api.brevo.com/v3/senders}"

sender_domain="${AAL_BREVO_SENDER_EMAIL##*@}"
[[ "${sender_domain,,}" == "${AAL_EMAIL_DOMAIN,,}" ]] || {
  echo "Sender must use the configured AAL email domain ($AAL_EMAIL_DOMAIN)" >&2
  exit 1
}

senders="$(curl --fail-with-body --silent --show-error --max-time 30 \
  -H "api-key: ${BREVO_API_KEY}" -H 'accept: application/json' "$BREVO_SENDERS_API_URL")"

jq -e --arg email "$AAL_BREVO_SENDER_EMAIL" '
  [(.senders // [])[] | select(.active == true and (.email | ascii_downcase) == ($email | ascii_downcase))] | length == 1
' <<<"$senders" >/dev/null || {
  echo "Sender $AAL_BREVO_SENDER_EMAIL is not present as an active Brevo sender" >&2
  exit 1
}

payload="$(jq -cn --arg sender "$AAL_BREVO_SENDER_EMAIL" --arg recipient "$UAT_EMAIL" \
  '{sender:{email:$sender,name:"Aviation Africa Logistics Ltd"},to:[{email:$recipient}],subject:"AAL production email UAT",htmlContent:"<p>AAL production email delivery UAT passed.</p>"}')"

response="$(curl --fail-with-body --silent --show-error --max-time 30 \
  -X POST "$BREVO_API_URL" \
  -H 'accept: application/json' \
  -H 'content-type: application/json' \
  -H "api-key: ${BREVO_API_KEY}" \
  -d "$payload")"

jq -e '.messageId and (.messageId | length > 0)' <<<"$response" >/dev/null

echo "Brevo transactional email UAT accepted by provider for sender=$AAL_BREVO_SENDER_EMAIL recipient=$UAT_EMAIL"
