# AAL production go-live runbook

This runbook defines the release evidence required before AAL handles real customer freight. It deliberately separates **code readiness** from **external-service readiness**. A source repository cannot create airline credentials, approve a carrier agreement, prove a live SMTP sender, or perform a production booking without the corresponding external account and UAT access.

## 1. Clean backend build

From a clean checkout:

```bash
cd backend
mvn -B clean verify
```

The GitHub production gate runs the same clean build and test command. Do not treat an existing `target/` directory as build evidence.

## 2. Clean frontend production build

```bash
cd frontend
npm ci
npx tsc --noEmit --pretty false
npm run lint --if-present
npm audit --omit=dev --audit-level=high
npm run build
```

## 3. CI/CD

The repository includes the restored workflows:

- `.github/workflows/aal-ci.yml` — build, tests, full frontend E2E, RLS/restore drills and security scan.
- `.github/workflows/aal-cd.yml` — container publishing with provenance/SBOM.
- `.github/workflows/aal-staging-acceptance.yml` — deployed HTTP/CORS/load/UAT acceptance.
- `.github/workflows/production.yml` — release gate, dependency/security scan, E2E and container scan.

A production merge should not bypass these gates.

## 4. Tests

The backend suite is executed by Maven. The frontend suite is executed by Playwright after building and starting the production Next.js server. Authenticated Playwright tests remain skipped unless UAT credentials are deliberately supplied.

Critical internal routes tested by Playwright include `/air-cargo`, `/air-cargo/bookings` and `/air-cargo/integrations`; a protected route may redirect to login but must not become a framework-level 404.

## 5. PostgreSQL tenant isolation

Run the real database drill with an administrative PostgreSQL role and the application's restricted role name:

```bash
PGHOST=... PGDATABASE=aal PGUSER=<admin-role> PGPASSWORD=... \
APP_DB_ROLE=aal_app ./scripts/security/tenant-isolation-drill.sh
```

The drill checks every public table containing `tenant_id` for RLS + FORCE RLS + a policy tied to `app.current_tenant`, then executes a negative read/write probe under the application role.

## 6. Backup and restore

Create a verified custom-format backup:

```bash
PGHOST=... PGDATABASE=aal PGUSER=aal_backup PGPASSWORD=<backup-role-password> \
BACKUP_DIR=/var/backups/aal ./scripts/backup.sh
```

Perform the restore drill into an isolated database:

```bash
RESTORE_DATABASE=aal_restore BACKUP_FILE=/var/backups/aal/<backup>.dump \
PGHOST=... PGUSER=<admin-role> PGPASSWORD=... \
./scripts/restore-drill.sh
```

The drill verifies archive readability, restored core records, Flyway history and tenant RLS protection. A fresh `docker-compose.production.yml` database volume automatically creates the restricted application role and the backup role from the supplied environment variables. Existing managed databases still require an explicit role-creation/preflight step.

## 7. Vulnerability scanning

The CI production gate runs OWASP Dependency-Check, `npm audit`, Trivy filesystem/container scans and Gitleaks. High/critical findings should be resolved or explicitly handled by the organization before release.

## 8. Basic load test

The staging workflow runs both existing k6 scenarios. Default thresholds are intentionally conservative:

- public quote: HTTP failure rate `< 2%`, p95 `< 1.5s`, p99 `< 3s`
- authenticated shipment search: HTTP failure rate `< 1%`, p95 `< 1s`, p99 `< 2s`

Run with a real staging API URL and a non-production load-test user/token. Never point these default scripts at a production customer dataset.

## 9. Browser security policy

The Next.js middleware supplies a per-request CSP nonce and removes `unsafe-eval` and script `unsafe-inline`. The security E2E checks the deployed response headers. Inline styles remain permitted because the current application uses React inline style declarations; removing that exception requires a broader frontend refactor.

## 10. Production CORS

Run:

```bash
BASE_URL=https://aal-ocst.onrender.com \
FRONTEND_URL=https://aal-a.vercel.app \
./scripts/provider-uat/cors-check.sh
```

The check requires the exact Vercel origin, credentials support, GET/OPTIONS methods and the headers actually used by AAL.

## 11. OTP and operational email

AAL production is configured for OTP + transactional email. The production sender must be an active, verified sender owned by the AAL email domain. Configure the Brevo key and sender only in Render/CI secrets.

Run:

```bash
BREVO_API_KEY=... \
AAL_BREVO_SENDER_EMAIL=no-reply@africalogisticaviation.com \
AAL_EMAIL_DOMAIN=africalogisticaviation.com \
UAT_EMAIL=<AAL-controlled test inbox> \
./scripts/provider-uat/brevo-delivery.sh
```

This proves the actual Brevo sender and sends a real UAT email. It is not simulated.

## 12. Configure an air-cargo provider

### CargoAi CargoCONNECT

The preferred multi-airline route in the existing architecture is a contracted CargoAi CargoCONNECT account. Obtain the exact AAL production/sandbox endpoint and credentials from CargoAi. Do not invent API paths. Configure the following backend environment variables on Render:

```text
AIRCARGO_PROVIDER_ENABLED=true
AIRCARGO_PROVIDER_CODE=AUTO
AIRCARGO_CARGOAI_BASE_URL=<CargoAi supplied endpoint>
AIRCARGO_CARGOAI_SEARCH_PATH=<CargoAi supplied search path>
AIRCARGO_CARGOAI_BOOKING_PATH=<CargoAi supplied booking path>
AIRCARGO_CARGOAI_TRACK_PATH=<CargoAi supplied tracking path>
AIRCARGO_CARGOAI_CANCEL_PATH=<CargoAi supplied cancellation path>
AIRCARGO_CARGOAI_API_KEY=<secret>
AIRCARGO_CARGOAI_USER_EMAIL=<AAL CargoAi user>
AIRCARGO_CARGOAI_USER_IATA=<AAL IATA>
AIRCARGO_CARGOAI_USER_CASS=<AAL CASS>
AIRCARGO_CARGOAI_USER_COUNTRY=RW
AIRCARGO_CARGOAI_COMPANY_NAME=Aviation Africa Logistics Ltd
```

Keep secrets out of Vercel and out of Git.

### Direct airlines

Qatar and Lufthansa currently have adapters in AAL, but their configured capabilities are intentionally narrower than a full airline booking integration. Enable only the documented capabilities that the carrier actually provisions. For Lufthansa smartBooking, obtain the partner credentials and endpoint through Lufthansa Cargo onboarding before enabling booking.

## 13. Provider UAT search

After credentials are configured:

```bash
BASE_URL=https://aal-ocst.onrender.com \
ACCESS_TOKEN=<AAL UAT operator token> \
PROVIDER_CODE=CARGOAI ORIGIN=KGL DESTINATION=NBO WEIGHT_KG=100 \
./scripts/provider-uat/aircargo-search.sh
```

A pass requires a real provider `CONNECTED...` response. A carrier in the directory alone never counts as live connectivity.

## 14–15. Provider booking and reconciliation

Use an airline/provider-approved test shipment. Live mutation is explicitly gated:

```bash
ALLOW_LIVE_BOOKING_UAT=true \
BASE_URL=... ACCESS_TOKEN=... SHIPMENT_ID=... \
ORIGIN=KGL DESTINATION=NBO WEIGHT_KG=100 \
./scripts/provider-uat/aircargo-lifecycle-uat.sh
```

The script selects only a provider-backed `bookable` offer, submits the real booking, polls the AAL booking record and requires `CONFIRMED`. Unknown outcomes remain `UNKNOWN/RECONCILING` rather than being falsely marked failed.

## 16. Provider failure/unknown outcome

The code's booking state machine and `ExternalOperationException` preserve unknown mutation outcomes for reconciliation. Unit tests cover the transition from `PENDING_PROVIDER` to `UNKNOWN` and onward reconciliation. The deployed UAT should still exercise a provider-approved timeout/failure case where the provider supports it.

## 17. Cancellation/amendment

The lifecycle UAT reads the live provider capabilities first. It performs amendment and cancellation only when the provider reports those capabilities and the corresponding UAT flags are enabled.

## 18. AWB/e-AWB

The AAL domain already contains AWB validation and provider submission contracts. Mark an external e-AWB integration live only after the carrier/provider supplies the production API/EDI/ONE Record contract and an accepted UAT transaction.

## 19. Flight tracking

Use a real tracking provider account where configured. The existing Aviationstack smoke test proves provider reachability; it does not imply that the airline booking adapter is live.

## 20. Operational notifications

Run the notification pipeline UAT after Brevo is configured:

```bash
BASE_URL=... ACCESS_TOKEN=... SHIPMENT_ID=... UAT_EMAIL=<AAL inbox> \
./scripts/provider-uat/notification-pipeline-smoke.sh
```

The queue must progress to `SENT`. Production configuration prevents the silent logging adapter from masquerading as a real notification transport.

## Final release rule

AAL may be deployed as production software only when the automated code/security gates pass **and** the external UAT gates required for the features being used are green. The source intentionally refuses to represent an unconfigured airline provider, unverified sender, or untested external booking contract as live.
