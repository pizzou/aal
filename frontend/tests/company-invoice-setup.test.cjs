const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const frontend = path.resolve(__dirname, '..');
const setup = fs.readFileSync(path.join(frontend, 'app/billing/setup/page.tsx'), 'utf8');
const backend = fs.readFileSync(
  path.resolve(frontend, '../backend/src/main/java/com/logiplatform/service/FinancialHardeningService.java'),
  'utf8',
);

test('setup validates backend readiness instead of assuming the company is ready', () => {
  assert.match(setup, /\/api\/billing\/invoice-readiness\?currency=/);
  assert.match(setup, /readiness\?\.ready === true/);
  assert.match(setup, /Complete and verify company setup before creating an invoice/);
});

test('company identity is saved only by an authorized, explicitly confirmed user', () => {
  assert.match(setup, /role === "ADMIN" \|\| role === "FINANCE"/);
  assert.match(setup, /if \(!verified\)/);
  assert.match(setup, /method: "POST"/);
  assert.match(setup, /defaultForInvoicing: true/);
});

test('RRA choices are statutory presets, not arbitrary invoice percentages', () => {
  assert.match(backend, /"RW"\.equals\(normalizedCountry\)/);
  assert.match(backend, /ensureRwandaInvoiceTreatments\(t, normalizedCode\)/);
  assert.match(backend, /RwandaInvoiceTaxPolicy\.treatments\(\)/);
  assert.match(backend, /taxCode\.startsWith\("RRA-RW-"\)/);
  assert.doesNotMatch(setup, /\/api\/finance\/hardening\/tax-rules"\s*,?\s*\{\s*method:\s*"POST"/);
});

test('company VAT-registration status is verified once, before invoicing', () => {
  assert.match(setup, /\/api\/finance\/hardening\/rwanda-vat-registration/);
  assert.match(setup, /vatRegistration === "REGISTERED"/);
  assert.match(setup, /vat_registration_status/);
  assert.match(setup, /Continue to customer invoicing/);
});

test('billing allows only server-approved RRA treatments and requires evidence where applicable', () => {
  const billing = fs.readFileSync(path.join(frontend, 'app/billing/page.tsx'), 'utf8');
  assert.match(billing, /\/api\/billing\/tax-treatments/);
  assert.match(billing, /invoice\/rra-preview/);
  assert.match(billing, /taxEvidenceReference/);
  assert.match(billing, /taxPreview\.treatmentCode !== taxTreatment/);
});
