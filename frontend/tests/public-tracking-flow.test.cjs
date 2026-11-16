// Dependency-free source contract checks. Full Spring + PostgreSQL integration
// tests are in PublicTrackingTest / TrackingReferenceCodeTest.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = (file) => fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');

test('shipment creation persists a generated public token and the CREATED milestone', () => {
  const shipment = read('backend/src/main/java/com/logiplatform/model/Shipment.java');
  const service = read('backend/src/main/java/com/logiplatform/service/ShipmentService.java');
  const migration = read('backend/src/main/resources/db/migration/V9__public_tracking_token.sql');
  assert.match(shipment, /trackingToken = UUID\.randomUUID\(\)/);
  assert.match(service, /documentSequences\.nextShipmentNumber\(\)/);
  assert.match(service, /shipmentRepository\.saveAndFlush\(shipment\)/);
  assert.match(service, /TrackingEventType\.CREATED/);
  assert.match(migration, /UNIQUE INDEX IF NOT EXISTS idx_shipments_tracking_token_unique/);
});

test('public tracking accepts secure tracking numbers but not enumerable shipment IDs', () => {
  const reference = read('backend/src/main/java/com/logiplatform/service/TrackingReferenceCode.java');
  const controller = read('backend/src/main/java/com/logiplatform/controller/PublicTrackingController.java');
  assert.match(reference, /PREFIX = "AAL-TRK-"/);
  assert.match(reference, /Tracking token is required/);
  assert.match(controller, /findByReference\(token\)/);
  assert.doesNotMatch(controller, /@PathVariable UUID token/);
});

test('public tracking queries only explicitly customer-visible issued documents', () => {
  const service = read('backend/src/main/java/com/logiplatform/service/PublicTrackingService.java');
  assert.match(service, /WHERE tracking_token = \?/);
  assert.match(service, /tracking_revoked = false/);
  assert.match(service, /tracking_expires_at IS NULL OR tracking_expires_at > now\(\)/);
  assert.match(service, /d\.customer_visible = true/);
  assert.match(service, /d\.status <> 'DRAFT'/);
  assert.doesNotMatch(service, /FROM customs_declarations d/);
  assert.match(service, /NULL::text AS notes/);
});

test('customer-facing screens accept full tracking links and label tracking numbers accurately', () => {
  const tracking = read('frontend/lib/tracking-reference.ts');
  const home = read('frontend/app/page.tsx');
  const track = read('frontend/app/track/page.tsx');
  const booking = read('frontend/app/book/BookClient.tsx');
  const detail = read('frontend/app/shipments/[id]/page.tsx');
  assert.match(tracking, /function normalizeTrackingInput/);
  assert.match(tracking, /function trackingNumberFromToken/);
  assert.match(home, /normalizeTrackingInput\(reference\)/);
  assert.match(track, /normalizeTrackingInput\(reference\)/);
  assert.match(booking, /trackingNumberFromToken\(result\.trackingToken\)/);
  assert.match(detail, /Customer tracking number \(secure\)/);
});

test('feedback failure cannot hide successful tracking results', () => {
  const result = read('frontend/app/track/[token]/page.tsx');
  assert.match(result, /const \[feedbackError, setFeedbackError\] = useState/);
  assert.match(result, /setFeedbackError\(/);
  assert.match(result, /\{feedbackError && <p role="alert">/);
});

test('air-cargo ETA polls flight-assigned shipments even without legacy weight_kg', () => {
  const poller = read('backend/src/main/java/com/logiplatform/service/AirCargoTrackingScheduler.java');
  const repo = read('backend/src/main/java/com/logiplatform/repository/ShipmentRepository.java');
  assert.match(poller, /findAllByTenantIdAndStatusAndTransportModeAndFlightNumberIsNotNull/);
  assert.match(repo, /findAllByTenantIdAndStatusAndTransportModeAndFlightNumberIsNotNull/);
  assert.doesNotMatch(poller, /findAllByTenantIdAndWeightKgIsNotNullAndStatus/);
});

