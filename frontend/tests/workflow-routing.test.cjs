// Source-level regression checks for the seven-stage internal operating model.
// These complement (and DO NOT replace) backend integration + browser tests.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

test('internal navigation shows seven steps in the requested order', () => {
  const source = read('frontend/components/AppShell.tsx');
  const labels = ['1. Customer', '2. Quotes', '3. Shipment', '4. Delivery / POD', '5. Invoice', '6. Payment', '7. Report'];
  let previous = -1;
  for (const label of labels) {
    const position = source.indexOf(`label: "${label}"`);
    assert.ok(position > previous, `${label} should follow the prior stage`);
    previous = position;
  }
});

test('accepted quotes are converted through the canonical endpoint', () => {
  const source = read('frontend/app/commercial/page.tsx');
  assert.match(source, /convertAcceptedQuote\(\s*quote\.id,\s*route,?\s*\)/);
  assert.match(source, /Create shipment →/);
  assert.match(source, /q.status\?\.toUpperCase\(\) === "WON"/);
});

test('AWB preparation never redirects directly to billing', () => {
  const source = read('frontend/app/air-cargo/awb/page.tsx');
  assert.doesNotMatch(source, /router\.replace\(\s*hasBillableValue/);
  assert.match(source, /router\.replace\(\s*`\/shipments\//);
});

test('shipment creation never immediately redirects to billing', () => {
  const source = read('frontend/components/ShipmentCreateForm.tsx');
  assert.doesNotMatch(source, /router\.replace\(`\/billing\?/);
});

test('delivery screen submits real POD through the operations API', () => {
  const source = read('frontend/app/deliveries/page.tsx');
  assert.match(source, /await operationsApi\.createPod/);
  assert.match(source, /confirmDelivered/);
});

test('financial issue path checks delivery and real POD', () => {
  const source = read('backend/src/main/java/com/logiplatform/service/BillingService.java');
  assert.match(source, /assertDeliveredWithPod\(shipment, tenantId\)/);
  assert.match(source, /proofOfDeliveryRepository\.hasSuccessfulDeliveryEvidence/);
});

test('conversion holds a quote lock and never silently defaults unknown modes to road', () => {
  const source = read('backend/src/main/java/com/logiplatform/service/CommercialOperationsService.java');
  assert.match(source, /converted_shipment_id FROM commercial_quotes.*FOR UPDATE/);
  assert.match(source, /Unsupported quotation service type/);
  assert.match(source, /Existing shipment returned; no duplicate created/);
});

test('payments originate from an existing invoice, not a shipment form', () => {
  const source = read('frontend/app/payments/page.tsx');
  assert.match(source, /\/billing\?invoiceId=/);
  assert.match(read('frontend/app/billing/page.tsx'), /Idempotency-Key/);
});

test('commercial references are server-generated, with read-only quote and customer numbers', () => {
  const seq = read('backend/src/main/java/com/logiplatform/service/FinanceDocumentSequenceService.java');
  const service = read('backend/src/main/java/com/logiplatform/service/CommercialOperationsService.java');
  const quoteForm = read('frontend/app/commercial/page.tsx');
  const customerForm = read('frontend/components/CustomerWorkspace.tsx');
  for (const name of ['nextCustomerNumber', 'nextQuoteNumber', 'nextTaskNumber', 'nextExpenseNumber', 'nextPartnerNumber']) {
    assert.match(seq, new RegExp(`public String ${name}\\(`));
    assert.match(service, new RegExp(`documentSequences\\.${name}\\(`));
  }
  assert.match(quoteForm, /readOnly=\{k === "quoteId"\}/);
  assert.match(customerForm, /clientId: undefined/);
  assert.match(read('backend/src/main/java/com/logiplatform/service/ShipmentService.java'), /String reference = nextShipmentReference\(\)/);
});

test('shipment selectors replace manual UUID input in operations workspaces', () => {
  for (const file of ['frontend/app/mobile-ops/page.tsx', 'frontend/app/exceptions/page.tsx', 'frontend/app/trips/page.tsx']) {
    const source = read(file);
    assert.match(source, /shipmentsApi\.list\(/);
    assert.match(source, /<select[\s\S]*?shipmentId/);
    assert.doesNotMatch(source, /placeholder="Shipment UUID"/);
  }
});

test('shipment and invoice identifiers cannot be overridden by client input', () => {
  const shipment = read('backend/src/main/java/com/logiplatform/service/ShipmentService.java');
  const billing = read('backend/src/main/java/com/logiplatform/service/BillingService.java');
  assert.match(shipment, /String reference = nextShipmentReference\(\)/);
  assert.match(shipment, /Invoice numbers are assigned automatically by Billing/);
  assert.match(billing, /Invoice number is assigned automatically/);
  assert.match(billing, /String invoiceNo = documentSequences\.nextInvoiceNumber\(\)/);
});
