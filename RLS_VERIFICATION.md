# Row-Level Security — Verified Against Real Postgres

This is not a claim — it's a record of an actual test run. On 2026-09-06, all five
Flyway migrations (V1–V5) were applied to a real Postgres 16.15 instance, and RLS
enforcement was tested by connecting as `logi_app`, the exact non-superuser role
production should run as (see `scripts/create_app_role.sql`).

## What was tested and confirmed

1. **Cross-tenant SELECT is blocked.** A session scoped to Tenant A via
   `SELECT set_config('app.current_tenant', '<tenant-a-uuid>', false)` saw only Tenant
   A's shipment. Switching the session variable to Tenant B saw only Tenant B's.

2. **Fail-closed, not fail-open.** A session with `app.current_tenant` never set saw
   **zero rows**, not all rows. This matters more than the isolation test above — a
   bug that defaults to "show everything" is far worse than one that defaults to
   "show nothing."

3. **Cross-tenant INSERT is rejected at the database level**, independent of
   application code:
   ```
   ERROR:  new row violates row-level security policy for table "shipments"
   ```
   This was triggered by a session scoped to Tenant A attempting to insert a row
   with Tenant B's `tenant_id` — exactly the kind of bug a compromised or buggy
   application layer could otherwise produce.

4. **The same isolation holds for the WMS tables** (`warehouses`, `inventory_items`)
   and the tracking-events table (`shipment_tracking_events`), added in later
   migrations — not just the tables from the original design.

5. **The negative-stock CHECK constraint works independent of RLS and independent
   of application code.** A direct `UPDATE ... SET quantity_on_hand = -5`, run as
   the Postgres superuser (which bypasses RLS entirely), was still rejected:
   ```
   ERROR:  new row for relation "inventory_items" violates check constraint "chk_quantity_non_negative"
   ```
   This is a second, independent layer protecting stock integrity — even a bug that
   somehow bypassed both the application layer and RLS still can't corrupt inventory
   counts.

6. **`FORCE ROW LEVEL SECURITY`** was added to every policy after this verification,
   as defense-in-depth for the case where the app is ever misconfigured to run as
   the table-owning role. Full migration re-run confirmed this doesn't break anything
   and RLS behavior is unchanged for the correctly-configured `logi_app` role.

## What was confirmed NOT to be a protection, correctly
The Postgres superuser (`postgres`) — and any superuser — bypasses RLS entirely,
always, with no configuration able to change that. This was verified directly (the
superuser query returned both tenants' rows even with `FORCE ROW LEVEL SECURITY` in
place). **This is why `scripts/create_app_role.sql` and running the app under
`logi_app`, never a superuser, is not optional — it's the one thing that makes every
policy above mean anything.**

## What is still NOT verified
- This tested the SQL/database layer directly via `psql`, not the actual Spring Boot
  application code path (`TenantAwareDataSource`, `JwtAuthenticationFilter`, the
  repository layer). Those still haven't compiled anywhere (see main README) — this
  verification derisks the database design, not the Java code that's supposed to
  drive it correctly.
- Concurrent writes under load (the WMS pessimistic lock) — this verification was
  single-connection, sequential.
- The CI workflow (`.github/workflows/ci.yml`) added alongside this verification will
  run the actual Spring Boot test suite on every push once this is in a real GitHub
  repo — that's the next real checkpoint, not this document.

## WMS row lock — concurrency-verified, not just written (update 2026-09-06)
The `PESSIMISTIC_WRITE` lock in `InventoryItemRepository.findByIdAndTenantIdForUpdate`
was tested with two genuinely concurrent transactions against the same real Postgres
instance, run as the actual `logi_app` role:

**With the lock** (`SELECT ... FOR UPDATE`): Session 2 started 0.5s after Session 1,
which held the lock for 3 seconds. Session 2's lock acquisition **blocked** for the
full 3 seconds — its `SELECT` didn't return until Session 1 committed, at which point
it correctly saw the post-Session-1 value (70, not the stale 100). Final result after
both a -30 and a -20 movement: **exactly 50**. Zero lost updates.

```
[session1] 16:38:54.156 starting, will hold lock for 3s
[session2] 16:38:54.657 starting, attempting FOR UPDATE lock
  -- session2's SELECT ... FOR UPDATE blocks here for ~2.5s --
[session1] 16:38:57.224 committed
[session2] 16:38:57.225 committed   (unblocked immediately after session1's commit)
Final quantity_on_hand: 50, version: 2
```

**Without the lock** (plain `SELECT`, identical timing, identical two movements): both
sessions read the stale value of 100 and computed their update independently. Final
result: **70** — one of the two movements (the -20) silently vanished, overwritten by
the other transaction's commit. This is the exact lost-update bug the row lock exists
to prevent, reproduced directly as a contrast test.

This confirms the lock is real, working protection under a genuine two-writer race —
not just code that looks correct. It does NOT confirm behavior under high-concurrency
load (dozens of simultaneous writers, connection-pool exhaustion under lock
contention) — that needs a proper load test (e.g. k6 or Gatling against the real
endpoint), which is still outstanding.

## Backup/restore — verified for real, including a real gotcha found along the way
`pg_dump -Fc` + `pg_restore` was run end-to-end against this exact schema:
- Full dump, restore into a fresh database, row counts compared: **identical** (2 shipments in both).
- All 6 RLS policies present in the restored database, confirmed via `pg_policies` —
  RLS survives the dump/restore cycle correctly.

**Real gotcha found by actually running it**: `pg_dump` executed as the normal
tenant-scoped `logi_app` role **fails outright** on every RLS-protected table:
```
pg_dump: error: query failed: ERROR:  query would be affected by row-level security policy for table "inventory_items"
```
This makes sense once you think about it — a backup isn't scoped to one tenant, and
`FORCE ROW LEVEL SECURITY` correctly has no way to let an unscoped bulk COPY through.
The fix, applied and reverified: a dedicated `logi_backup` role with `BYPASSRLS`
(not a full superuser — least privilege), created via `scripts/create_backup_role.sql`.
With that role, the backup succeeded and captured all tenants' data correctly, as a
real backup must. `scripts/backup.sh` now documents this requirement directly.

## GPS tracking — real Postgres + real Redis, both verified before wiring together (2026-09-06)
Rather than build the Kafka streaming pipeline discussed in earlier design work (which
can't be verified in this sandbox — no route to Kafka distributions), GPS tracking was
built as REST ingestion + Postgres history + Redis latest-position cache, with both
storage halves verified independently before writing the Java service that combines them:

**Postgres** (`vehicle_gps_positions`, migration V8): tenant isolation confirmed
correct even in a deliberately adversarial case — two different tenants recording
positions for the SAME `vehicle_id` value (a real UUID collision-style stress test,
not just two different vehicles) — Tenant B's query returned only its own row. The
latitude/longitude CHECK constraints were also confirmed to reject invalid values
(`latitude=999` was rejected with the expected constraint violation) independent of
any application-level validation.

**Redis** (latest-position cache): `SET`/`GET` on a per-vehicle key confirmed to
store and overwrite correctly, and a query for an unrelated vehicle's key confirmed
to return nothing — proving per-vehicle key isolation, not just "Redis works."

**A bug caught before it shipped**: the initial `GpsTrackingService.latest()`
implementation didn't wrap its Redis read in a try/catch, which meant a Redis outage
would have thrown a 500 instead of gracefully falling back to Postgres — directly
contradicting the resilience behavior documented in the class's own Javadoc. Fixed
before this was packaged. This is exactly the kind of bug that "written carefully"
doesn't catch and that only shows up when you actually think through the failure path
— a reminder of why the Java layer here still needs real compilation and testing
(see main README), not just careful review.

## Public tracking portal — verified the exception is necessary, then verified the fix (2026-09-06)
The public tracking token (migration V9) is the third narrow, documented exception to
tenant-scoped access — see PublicTrackingService's class Javadoc for the full
reasoning. Before writing that service, the underlying claim was tested directly:

1. **Confirmed the problem is real**: querying `shipments` by the correct
   `tracking_token`, through the normal RLS-protected connection (`logi_app`) with no
   tenant context set (exactly the situation a real anonymous customer request would
   be in), returned **zero rows** — even with the exactly correct token. This proves
   the exception is necessary, not just convenient: a public tracking feature is
   fundamentally incompatible with "always scope by tenant" without a documented,
   narrow escape hatch.
2. **Confirmed the fix works**: the same query run through the `logi_backup`
   (BYPASSRLS) role — standing in for the raw datasource pattern the Java service
   uses — correctly returned the one matching shipment, and the exact JOIN query used
   for tracking-event history was verified to return correctly ordered results.
3. **Confirmed the failure mode is safe**: a random/guessed token returns nothing, no
   error detail, no distinction between "malformed," "never existed," or "belonged to
   a deleted shipment" — all collapse to the same 404, which is the correct behavior
   for something a token's 128-bit unguessability is supposed to protect.
4. **Confirmed the uniqueness constraint holds**: a direct attempt to insert a
   duplicate token was rejected by Postgres with the expected constraint violation —
   this token is a security boundary, and its uniqueness isn't just assumed.

## IoT sensor monitoring — real Postgres constraints and RLS verified (2026-09-06)
`shipment_sensor_readings` and `shipment_sensor_thresholds` (migration V10) were
applied to real Postgres and tested directly, same standard as every other table:
- The `chk_at_least_one_reading` constraint correctly rejected a row with both
  temperature and humidity null.
- The `chk_humidity_range` constraint correctly rejected humidity=150 (out of 0-100).
- A valid reading (-12°C, 45%) succeeded.
- Tenant B's query for sensor readings returned zero rows for Tenant A's data, RLS
  confirmed working on both new tables.

The actual point of the feature — an out-of-range reading auto-creating an EXCEPTION
event on the shipment's tracking timeline — is covered by `SensorMonitoringTest`
(H2-backed, same scope caveat as other domain logic tests) rather than a raw SQL
check, since that behavior lives in `SensorMonitoringService`, not the schema.

## Load planning — algorithm correctness verified in Python BEFORE the Java port (2026-09-06)
Since this sandbox cannot compile Java, the 0/1 knapsack algorithm's correctness was
verified independently in Python first — a technique specific to working around that
constraint, not used elsewhere in this project because other features didn't need it:

```
Test 1 (perfect fit expected 1000): total=1000, selected=['S2', 'S1']
Test 2 (expected 700 via S3+S4): total=700, selected=['S4', 'S3']
Test 3 (heavy item must be excluded, expected 90): total=90, selected=['Light2', 'Light1']
Test 4 (5x3kg into 10kg cap, expected 9): total=9, selected=['C', 'B', 'A']
Test 5 (no shipments, expected 0): total=0, selected=[]
ALL TESTS PASSED
```

The Java port (`LoadPlanningService`) mirrors this logic line-for-line, and
`LoadPlanningTest` re-asserts the SAME five expected outcomes against the Java
version — so a porting mistake (an off-by-one in the DP loop, a backtracking bug)
would be caught by that test even though the Java itself has never compiled in this
sandbox. This is two different, complementary kinds of confidence: Python proves the
ALGORITHM is right; the Java test proves the PORT matches it, once CI can actually
run it. Neither substitutes for the other.

A real mistake was also caught and fixed during this work: the frontend's TypeScript
`Shipment` interface was missing the new `weightKg` field after it was added to the
backend response, and `npm run build` failed with a real, specific compile error
pointing at the exact line. Fixed immediately, rebuilt, confirmed clean — a small,
concrete example of exactly why "build after every change" has been the standing
practice throughout this project rather than an occasional check.

## Performance dashboard — every aggregation query verified against real Postgres before writing Java (2026-09-06)
Four queries power the reporting dashboard. Each was run directly against real
Postgres, on a seeded scenario with a known correct answer, BEFORE the Java that
wraps them was written:

1. **Carrier lead time**: seeded a shipment with BOOKED and DELIVERED tracking events
   exactly 5 hours apart. Query returned `avg_lead_time_hours = 5.00` — exact match.
2. **Shipment status breakdown**: seeded shipments in DELIVERED/IN_TRANSIT/PENDING;
   query returned the exact counts (1/1/1).
3. **Carrier exception rate**: seeded 2 shipments for one carrier, 1 with an
   EXCEPTION event; query correctly returned total=2, with_exceptions=1.
4. **Trip stats**: seeded trips in COMPLETED/CANCELLED/PLANNED; query returned exact
   counts (1/1/0/1). Also verified the zero-trips edge case for a tenant with no
   trips at all returns NULL (which JDBC's `getLong()` correctly converts to 0, not
   an error) rather than failing.

All four were also confirmed tenant-isolated: a second tenant's dashboard query
returned zero rows / empty results despite the first tenant's data existing in the
same tables — same fail-closed pattern verified everywhere else in this codebase.

One portability decision made directly from this verification: the trip-stats query
was initially written using Postgres's `FILTER (WHERE ...)` clause (confirmed working
in Postgres), but rewritten to the more portable `SUM(CASE WHEN ... THEN 1 ELSE 0
END)` form before shipping, specifically so the H2-backed Java test suite has a
realistic chance of passing too, not just the Postgres-side SQL. Re-verified the
rewritten version against real Postgres to confirm it produces identical results.

## AWB document generation — layout verified visually before the Java port (2026-09-07)
Since layout correctness (no overlapping text, fits the page, boxes align) isn't the
kind of thing SQL or algorithm-output verification catches, the AWB document layout
was prototyped in Python/reportlab first, rendered to an actual PDF, converted to a
PNG, and visually inspected — confirming clean field boxes, no overlap, correct page
fit — before being ported to Java/PDFBox. Every coordinate in the Java version
(AwbDocumentService) is a direct mm-to-points translation of that verified prototype,
not a fresh design. The one thing NOT verified: whether Apache PDFBox's specific API
calls (setNonStrokingColor, addRect, etc.) compile correctly — same standing caveat
as all Java in this project, resolved only by real CI.

Also worth being explicit about: this generates a correctly-laid-out AWB-shaped
DOCUMENT from data already in the system. It does not validate against or submit to
any real IATA/carrier system — that submission step is one of the six items this
project has consistently flagged as impossible to build without real carrier
credentials. The generated PDF includes an explicit, visible notice about the
shipper/consignee data gap (this platform stores addresses as text, not structured
company/contact records) rather than silently presenting incomplete data as complete.

## Booking & Capacity — integration-ready mock, not a real integration (2026-09-07)
CarrierGatewayPort defines the seam for airline schedules, capacity signals, rate
quotes, and eBooking. MockCarrierGatewayAdapter is the only implementation, and every
response it returns carries `mockData: true` plus a disclaimer string as an actual
API field — not just a code comment — specifically so a UI or another service
consuming it cannot accidentally present mock data as real. The frontend booking-demo
page repeats the same warning prominently, and the API routes live under
`/api/booking/mock/**` so the mock status is visible in the URL itself.

This is architecture, not a feature: a real airline integration would implement the
same `CarrierGatewayPort` interface and be swapped in via Spring dependency
injection, with no changes needed anywhere else in the codebase. That real
implementation cannot be written without an actual carrier's API access — no
verification technique in this project (Python prototyping, real Postgres, real
Redis) substitutes for a real airline granting API credentials, and none of the
verification done elsewhere in this document should be read as implying otherwise
for this feature.

## Notifications — real Postgres RLS verified, plus a real config bug caught before shipping (2026-09-08)
`notifications` table and `shipments.notification_email` (migration V12) were applied
to real Postgres and RLS confirmed working: a notification seeded under Tenant A was
invisible to Tenant B (0 rows), visible to Tenant A (1 row), same fail-closed pattern
verified everywhere else in this project.

**Design note on why this feature is different from the airline/booking mock**: email
delivery uses SMTP, a universal protocol every provider supports (Gmail, SendGrid,
AWS SES, a company's own server) with no special partnership required — unlike
airline/customs APIs, which need a specific vendor's proprietary access. So
`SmtpNotificationAdapter` is a genuinely complete implementation, not a permanent
stand-in — it activates automatically once real SMTP credentials are configured via
`notifications.smtp.enabled=true` plus `SMTP_HOST`/`SMTP_USERNAME`/`SMTP_PASSWORD`.
Until then, `LoggingNotificationAdapter` is active by default: every notification
attempt is still recorded to the `notifications` table (audit trail always complete),
it just never actually sends.

**A real bug caught mid-edit, not after**: while wiring SMTP configuration into
`application.yml`, a second top-level `spring:` block was accidentally introduced to
hold the `mail:` settings. YAML does not merge duplicate top-level keys — Spring
would have silently used only the LAST `spring:` block, meaning the datasource, JPA,
Flyway, and Redis configuration defined in the first block would have been wiped out
entirely at runtime. This is a category of bug that's easy to write and easy to miss
in review (both blocks look individually correct), and it wouldn't have been caught
by anything except either careful re-reading of the file or a real Spring context
startup — which, notably, this sandbox cannot do. Caught by re-reading the file
before moving on, and fixed by merging `mail:` into the single existing `spring:`
block. Documented here rather than silently fixed, since it's a good illustration of
why "written carefully" and "verified" remain different claims throughout this
project — this one was neither compiler-verified nor found by tooling, just by a
second pass.

## Flight status — real API schema verified via search, parsing logic verified in Python (2026-09-08)
Unlike CarrierGatewayPort (airline booking), flight STATUS data has genuine
self-service public APIs. AviationStack's real, documented response schema was
confirmed via web search against their actual documentation (not invented):
```
{"data":[{"flight_status":"active","departure":{"iata":"SFO","delay":13,...},
          "arrival":{"iata":"DFW","delay":0,...}}]}
```
The delay-classification logic (null delay -> 0, not a crash; >=60min triggers
`significantDelay`) was verified in Python against that exact schema, including the
specific edge case (a flight with no reported delay yet returns `delay: null`, not
`0`) that would cause a `NullPointerException` on unboxing in Java if missed:
```
{'flight_status': 'active', 'departure_delay_minutes': 45, ..., 'significant_delay': False}
{'flight_status': 'active', 'departure_delay_minutes': 90, ..., 'significant_delay': True}
{'flight_status': 'active', 'departure_delay_minutes': 0, ..., 'significant_delay': False}  <- null->0 case
ALL CHECKS PASSED
```
`AviationStackFlightStatusAdapter` is a genuine, complete implementation against this
verified contract — requires a free API key (self-service signup, no carrier
relationship) to function. `NoOpFlightStatusAdapter` is the safe default. A
significant delay routes through the SAME `addTrackingEvent(EXCEPTION)` path already
built and tested for manually-reported exceptions — new data source, existing
verified pipeline, not a parallel system.

**Genuinely not verified**: an actual live call to api.aviationstack.com. This
sandbox's bash tool has no route to that domain (not on the allowlist), so the
adapter's HTTP call itself has never executed — only its response-parsing logic
(against the real documented schema) and the decision to build it this way (self-service
API vs. carrier partnership) were verified.

## Rate engine — arithmetic verified against real Postgres NUMERIC before the Java port (2026-09-08)
Explicitly RULES-BASED, not AI — every quote response includes `rateType:
"RULES_BASED"` as an actual API field for the same reason `mockData:true` exists on
the booking mock: so nothing downstream can mislabel it. The exact calculation was
verified with real Postgres NUMERIC arithmetic before being ported to Java
BigDecimal:
```
100kg @ $4.50/kg, 15% fuel surcharge: base=450.00, surcharge=67.50, total=517.50
5kg @ same rate, $50 minimum: raw=22.50, floored to min_charge=50.00
```
Both numbers are re-asserted exactly in `RateEngineTest` against the Java
implementation. BigDecimal is used throughout (never `double`) specifically to avoid
floating-point rounding errors in financial math — a well-known, easy-to-introduce
bug class this deliberately avoids.
