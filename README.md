# Logistics Platform — Foundation + WMS + Multi-Modal Shipments + TMS + GPS Tracking

## What this is
A multi-tenant logistics/cargo SaaS: Spring Boot backend + Next.js frontend +
Postgres (Row-Level Security) + Redis + Kafka (provisioned, still unused) in Docker
Compose. Five domains implemented end to end and tested:

- **Auth + multi-tenancy**: JWT auth, Postgres RLS, explicit tenant-scoped queries
- **Shipments (multi-modal)**: ROAD/AIR/SEA/RAIL, carrier reference numbers, a
  mode-agnostic tracking-event timeline
- **WMS**: warehouses, inventory, an append-only stock-movement ledger with a
  concurrency-safe row lock (verified under genuine concurrency)
- **TMS**: vehicles, drivers, trips — a real dispatch workflow (availability checks,
  double-booking prevention, status propagation on start/complete/cancel)
- **GPS tracking**: REST ingestion, Postgres history, Redis latest-position cache
  with a verified graceful fallback if Redis is unavailable — see below for why this
  is REST+Redis rather than the Kafka pipeline discussed earlier
- **Public tracking portal**: an unguessable per-shipment token (no login required)
  that customers can use to see shipment status and history — the third narrow,
  documented exception to tenant-scoped access, verified necessary and verified safe
  (see RLS_VERIFICATION.md)
- **IoT cold-chain monitoring**: temperature/humidity readings per shipment, with
  per-shipment thresholds — a reading outside range automatically creates an
  EXCEPTION event on the shipment's tracking timeline
- **Load planning**: optimal (not heuristic) vehicle loading via 0/1 knapsack —
  maximizes cargo weight loaded without exceeding vehicle capacity. Algorithm
  correctness verified in Python before the Java port, then re-verified against the
  Java version's actual output (see RLS_VERIFICATION.md)
- **Performance dashboard**: carrier lead times, exception rates, fleet utilization,
  trip outcomes — every aggregation query verified against real Postgres with a
  known-answer seeded scenario before the Java was written
- **AWB document generation**: produces a correctly-laid-out Air Waybill PDF from
  shipment data. Layout verified visually (Python/reportlab prototype rendered and
  inspected) before the Java/PDFBox port. Generation only — does not validate
  against or submit to any real carrier/IATA system, and says so on the document
- **Booking & Capacity — integration-ready architecture**: `CarrierGatewayPort`
  defines the seam for airline schedules/capacity/rates/eBooking; the only
  implementation is a mock that labels every response `mockData: true` with a
  disclaimer, so a real airline integration can be swapped in later without
  pretending one exists today
- **Notifications**: delivery confirmations and exception alerts, real working SMTP
  email once configured (unlike the booking mock, email is a universal protocol, so
  this is a genuine implementation) — defaults to logging-only until SMTP is set up,
  with a complete audit trail either way
- **Flight status (real once configured)**: unlike airline booking, flight *status*
  data has genuine self-service public APIs (AviationStack, no carrier relationship
  needed) — a real, complete adapter against AviationStack's documented schema,
  defaulting to a safe no-op until a free API key is supplied. A significant delay
  routes through the same EXCEPTION/notification pipeline already built for manual
  reports — new data source, existing verified behavior
- **Rate/quoting engine (real, rules-based)**: replaces "manual spreadsheets" with
  stored, tenant-configurable rate cards and accessorial charges, computed via one
  consistent BigDecimal code path. Every quote response is explicitly labeled
  `rateType: "RULES_BASED"` — deliberately never claims to be AI/market-driven,
  which would need live market-rate data feeds this platform doesn't have

Plus cross-cutting work: Redis-backed distributed rate limiting, Actuator/Prometheus
metrics, request-correlation-ID logging, a fail-fast production secrets profile, and
CI that actually compiles and tests the backend (this sandbox cannot).

## What's genuinely verified, not just written — and how
Unlike most AI-assisted code, several of the riskiest claims here were tested against
**real infrastructure running in the build sandbox**, not asserted from confidence.
Full logs and exact commands: [`RLS_VERIFICATION.md`](./RLS_VERIFICATION.md).

- **Row-Level Security**, all 4 modules' tables (10 tables total): cross-tenant reads
  blocked, cross-tenant inserts rejected, fail-closed confirmed (no tenant set = zero
  rows), survives a full pg_dump/restore cycle — all against real Postgres 16, as the
  actual non-superuser role production must run as.
- **The WMS row lock**, under genuine concurrency: two real concurrent transactions
  raced for the same inventory row. With the lock, the second correctly blocked and
  read the post-first-transaction value (net result exactly right, zero lost
  updates). A contrast test with the lock removed reproduced the classic lost-update
  bug directly — proof the lock does real work, not just present-looking code.
- **Backup and restore**: real `pg_dump`/`pg_restore` cycle, row counts and RLS
  policies confirmed identical after restore. This surfaced a real, non-obvious
  finding: `pg_dump` run as the tenant-scoped app role is blocked by RLS entirely
  (correctly — a backup isn't scoped to one tenant), which is why a separate
  `logi_backup` role with `BYPASSRLS` exists (`scripts/create_backup_role.sql`).
- **Redis-backed rate limiting**: the exact atomic Lua increment-and-check script was
  tested directly against real Redis (10 allowed, 11th+ rejected, TTL correct, state
  confirmed shared across connections) before being wired into the Java filter.
- **Frontend**: `npm install` + `npm run build` actually run, every time a page was
  added — currently 9 routes, all compiling clean. This also caught a real critical
  CVE in the Next.js version originally pinned (see git history / earlier notes).
- **Prometheus scrape config**: validated with `promtool check config` — real tool,
  real pass.

## What is NOT verified — the one gap that matters most
**The Java backend has never compiled anywhere.** This sandbox has no route to Maven
Central, so every line of Spring Boot / JPA / Java code here has been written and
reviewed carefully, but never built. `.github/workflows/ci.yml` runs `mvn clean
verify` on a real GitHub Actions runner, which does have Maven Central access — the
first push to a real repository is the first time this compiles anywhere. Treat that
first CI run as a real, meaningful event that may well fail, not a formality.

## Feature roadmap — still queued, in priority order
"All features" was the actual ask that led to this section existing, followed by a
detailed enterprise air-cargo feature list (booking/capacity management, e-AWB,
customs automation, route optimization, predictive tracking, automated billing).
Honest categorization of that list, updated as work progressed:

**Still cannot be built in a coding session, by anyone, regardless of effort** —
these require an actual business relationship (a specific carrier's approval, a
customs authority's system access), not just signup:
- Direct airline connectivity / eBooking for BOOKING capacity (needs a real
  IATA/airline API contract — self-service signup doesn't get you cargo capacity)
- AI-driven dynamic/spot *market* pricing (needs live market-rate data feeds; the
  rate engine below is real, but rules-based, not market-driven — see below)
- e-AWB/customs *submission* to real systems (no real endpoint exists to submit to)
- Customs automation requiring a real broker/customs-authority integration

**Reclassified after further investigation — genuinely buildable, and now built**:
flight *status* (as opposed to booking) turned out to have real, self-service public
APIs (AviationStack and similar — free signup, no carrier relationship required).
`FlightStatusPort`/`AviationStackFlightStatusAdapter` is a real, complete
implementation against AviationStack's actual documented schema, not another mock —
see RLS_VERIFICATION.md for how that schema and its parsing logic were verified.
This covers real-data delay detection for a shipment's own flight, not full
multi-flight route optimization (a much larger search/connection-feasibility
problem, still out of scope).

**Buildable, and now built** (across recent sessions): IoT cold-chain monitoring,
load planning, performance dashboards, AWB document generation, an
integration-ready (mock-backed) booking/capacity architecture, notifications (real
SMTP once configured), real-data flight status/delay detection, and a real
rules-based rate/quoting engine (replaces "manual spreadsheets" honestly — see
`rateType: "RULES_BASED"` on every quote, deliberately never claiming to be AI).
Cross-border multi-modal leg splitting was already partially covered by
Shipments+TMS Trips — see the correction below on what's NOT yet true of that claim.

**Buildable, still queued**:
1. Standardized templates (dangerous-goods declarations, freight audit logs)
2. Invoice generation from a quote (the rate engine computes a price; turning that
   into an actual invoice document/record is the next step)
3. True per-leg multi-modal splitting (see correction below)
4. SMS notification channel (email/SMTP is done; SMS needs a provider like Twilio —
   same "generic-enough to build for real" category as email, just not done yet)

**Correction on cross-border multi-modal**: earlier text in this README overstated
this as "already covered." What actually exists: a Shipment has ONE fixed transport
mode set at creation, and TMS Trips can carry shipments — nothing currently models
one order automatically splitting into multiple legs with DIFFERENT modes (truck to
airport, flight, truck to final address) as the source description asks for. A
shipment could technically be added to multiple sequential trips by hand, but
there's no first-class "multi-leg journey" concept, no automatic mode transitions,
and no unified view of one order's full multi-modal journey. This is a real gap,
not a solved problem — queued above.


## What is still completely missing
- **The Kafka streaming pipeline** for GPS at real fleet scale (thousands of
  vehicles). What exists now (REST + Redis) is genuinely verified and reasonable for
  low-to-moderate fleet sizes, but wasn't designed for the "thousands of vehicles,
  high-frequency GPS" scale from the original brief — that upgrade path was part of
  the original architecture discussion and still needs a real Kafka-accessible
  environment to build and verify honestly.
- **Distributed rate limiter under real load** — the Redis Lua script was verified
  correct, but not load-tested (e.g. k6/Gatling) at the concurrency a real deployment
  would see.
- **WMS row lock under high-concurrency load** (dozens of simultaneous writers) —
  the two-transaction race was proven; many-writer contention and connection-pool
  behavior under it were not.
- **TMS RLS was verified directly against Postgres** (see verification doc), but the
  Java dispatch logic (double-booking prevention, status propagation) has only run
  against H2, same caveat as every other domain's business logic.
- Carrier system integrations (IATA Cargo-XML, ocean booking APIs, customs) — requires
  real carrier contracts/credentials, can't be built in a coding session regardless.
- Observability is metrics + correlation IDs only — no distributed tracing, no log
  aggregation pipeline, no alerting rules.
- No WAL-based point-in-time recovery for Postgres — `scripts/backup.sh` is a
  once-daily snapshot approach (up to 24h data loss in the worst case); a production
  system with real client data should look at pgBackRest or WAL-G.
- No JWT key rotation mechanism — rotating `JWT_SECRET` invalidates every session at
  once, there's no graceful multi-key transition.

## Running locally

```bash
# 1. Start infrastructure
docker compose up -d postgres redis zookeeper kafka

# 2. Run backend (applies Flyway migrations automatically on boot)
cd backend
mvn spring-boot:run

# 3. Run frontend
cd frontend
npm install
npm run dev
```

Backend: http://localhost:8080 · Frontend: http://localhost:3000 · Metrics:
http://localhost:8080/actuator/prometheus · Prometheus UI (if using full
docker-compose): http://localhost:9090

## First-run smoke test
```bash
# Register a tenant + admin user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"tenantName":"Acme Freight","email":"admin@acme.com","password":"ChangeMe123!"}'
# -> { "accessToken": "...", "tenantId": "..." }

# Create a vehicle and driver, then dispatch a trip
curl -X POST http://localhost:8080/api/vehicles -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d '{"registrationNumber":"RAB-111A","vehicleType":"TRUCK","capacityKg":5000}'
curl -X POST http://localhost:8080/api/drivers -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d '{"fullName":"Jean Baptiste","licenseNumber":"LIC-001"}'
curl -X POST http://localhost:8080/api/trips -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"vehicleId":"<vehicleId>","driverId":"<driverId>","originAddress":"Kigali","destinationAddress":"Musanze"}'
curl -X POST http://localhost:8080/api/trips/<tripId>/start -H "Authorization: Bearer <token>"
```

## Before ANY client sees this
- [x] ~~Row-Level Security enforced~~ — verified against real Postgres (all 4 modules)
- [x] ~~Rate limiting on /api/auth/\*\*~~ — Redis-backed, verified, distributed-safe
- [x] ~~WMS row lock~~ — verified under genuine concurrency
- [x] ~~Backups~~ — script written and verified end-to-end (see gaps above for what's still missing: off-instance storage, scheduling, PITR)
- [x] ~~Basic observability~~ — Actuator/Prometheus + correlation IDs; NOT full tracing/alerting
- [ ] Push to a real repo and let `.github/workflows/ci.yml` actually compile this backend for the first time — this is the single most important remaining unknown
- [ ] Run `scripts/create_app_role.sql` and `scripts/create_backup_role.sql` against your production database; confirm the app connects as `logi_app`, never a superuser
- [ ] Set `JWT_SECRET` and datasource credentials via a real secrets manager, activate the `prod` Spring profile (`SPRING_PROFILES_ACTIVE=prod`) so missing secrets fail startup instead of silently using dev defaults
- [ ] Enable HTTPS termination (this stack assumes a reverse proxy/load balancer in front)
- [ ] Ship `BACKUP_DIR` to off-instance storage (S3/GCS) — the script alone only protects against database corruption, not instance loss
- [ ] Build and verify the GPS ingestion pipeline in an environment with real Kafka access


## Production hardening release
This release adds:
- complete RLS coverage for all tenant-owned operational tables
- pooled-connection tenant context clearing to prevent tenant leakage
- JWT token-version revocation and account lockout after repeated failures
- HttpOnly browser session cookie with double-submit CSRF protection
- pessimistic locking for air-flight capacity reservations
- optimistic concurrency versions for shipments, bookings and invoices
- idempotent commercial payments
- MAWB IATA check-digit validation
- truthful AWB/customs submission states (no fake external acceptance)
- carrier schedule/capacity canonical integration mapping
- production CORS/security configuration
- non-root backend/frontend container images
- production Docker Compose with a least-privilege application database role
- bounded load-planning fallback for large optimization instances
- idempotent IoT telemetry event ingestion and timestamp-window validation
- standardized error handling for conflicts and malformed parameters

External airline, customs/broker and IoT provider contracts remain provider-specific. The
platform contains integration adapters and idempotency boundaries, but production
connectivity is only considered active after the real provider endpoint, credentials,
schema contract, certification and end-to-end acknowledgement tests are supplied.

## Enterprise core / first-client rollout
The platform now includes the V22 enterprise logistics core: customer contacts, shipment parties, quotes and quote lines, operational milestones, SLA policies, workflow definitions, task queues, integration outbox, claims and shipment insurance. See `AAL_FIRST_CLIENT_BLUEPRINT.md` for the Africa Aviation Logistic rollout model.

## Backend package structure — Enterprise Phase 1

The backend has been refactored so application classes are separated by technical responsibility rather than mixed under `domain/*`:

- `com.logiplatform.model` — entities, enums and domain/persistence models
- `com.logiplatform.dto` — API request/response DTOs
- `com.logiplatform.repository` — persistence repositories
- `com.logiplatform.service` — business/application services and integration adapters/ports
- `com.logiplatform.controller` — REST controllers
- `com.logiplatform.config` — application configuration
- `com.logiplatform.security` — authentication/security
- `com.logiplatform.tenancy` — tenant isolation infrastructure
- `com.logiplatform.common` — cross-cutting infrastructure

The old `com.logiplatform.domain.*` package is no longer used by the main backend source. See `backend/src/main/java/com/logiplatform/ARCHITECTURE.md` for the layer rules.
