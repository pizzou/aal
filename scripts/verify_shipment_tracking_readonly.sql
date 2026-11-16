-- Run against a recent staging copy of the AAL PostgreSQL database.
-- All statements are read-only. No tenant data is modified.

-- 1. Most recently created shipments: two distinct identifiers.
SELECT id, tenant_id, reference_code, tracking_token, status, created_at
FROM shipments
ORDER BY created_at DESC
LIMIT 20;

-- 2. Every shipment must carry a public tracking token.
SELECT count(*) AS total_shipments,
       count(*) FILTER (WHERE tracking_token IS NULL) AS missing_tracking_tokens,
       count(DISTINCT tracking_token) AS unique_tracking_tokens,
       count(*) FILTER (WHERE reference_code IS NULL OR btrim(reference_code) = '') AS missing_shipment_numbers
FROM shipments;

-- 3. Confirm the unique DB index that protects secret tokens.
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename = 'shipments'
  AND indexname LIKE '%tracking_token%';

-- 4. Identify records with no tracking events (often older imported data).
SELECT s.id, s.reference_code, s.status, s.created_at,
       count(e.id) AS event_count
FROM shipments s
LEFT JOIN shipment_tracking_events e
  ON e.shipment_id = s.id AND e.tenant_id = s.tenant_id
GROUP BY s.id, s.reference_code, s.status, s.created_at
HAVING count(e.id) = 0
ORDER BY s.created_at DESC
LIMIT 50;

-- 5. Confirm the sample of most recent immutable timeline events.
SELECT s.reference_code, e.event_type, e.location, e.occurred_at
FROM shipment_tracking_events e
JOIN shipments s ON s.id = e.shipment_id AND s.tenant_id = e.tenant_id
ORDER BY e.occurred_at DESC
LIMIT 30;

-- 6. Customer-visible documents must be separately approved for publication.
SELECT customer_visible, status, count(*) AS document_count
FROM cargo_documents
GROUP BY customer_visible, status
ORDER BY customer_visible, status;
