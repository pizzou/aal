#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?Set PGHOST}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${PGUSER:?Set PGUSER}"

: "${EXPECTED_SHIPMENTS:?Set expected shipment count from source workbook}"
: "${EXPECTED_QUOTES:?Set expected quote count from source workbook}"
: "${EXPECTED_INVOICES:?Set expected invoice count from source workbook}"
: "${EXPECTED_CLIENTS:?Set expected client count from source workbook}"

psql -v ON_ERROR_STOP=1 <<SQL
WITH expected AS (
    SELECT
      ${EXPECTED_SHIPMENTS}::bigint AS shipments,
      ${EXPECTED_QUOTES}::bigint AS quotes,
      ${EXPECTED_INVOICES}::bigint AS invoices,
      ${EXPECTED_CLIENTS}::bigint AS clients
), actual AS (
    SELECT
      (SELECT count(*) FROM shipments) AS shipments,
      (SELECT count(*) FROM commercial_quotes) AS quotes,
      (SELECT count(*) FROM commercial_invoices) AS invoices,
      (SELECT count(*) FROM client_records) AS clients
)
SELECT
  expected.shipments, actual.shipments,
  expected.quotes, actual.quotes,
  expected.invoices, actual.invoices,
  expected.clients, actual.clients,
  (expected.shipments=actual.shipments
   AND expected.quotes=actual.quotes
   AND expected.invoices=actual.invoices
   AND expected.clients=actual.clients) AS counts_match
FROM expected, actual;
SQL
