# AAL workbook field mapping

The reviewed workbook `40.40 sample.xlsx` contains one transaction/allocation sheet.
The sample rows are **reference data only** and are intentionally not seeded.

| Workbook concept | AAL result |
|---|---|
| DATE | Existing canonical `commercial_payments.created_at` (payment date/time). No duplicate date column. |
| TOTAL INCOME | Existing canonical `commercial_payments.amount`. No duplicate income ledger. |
| PERCENTAGE | New `finance_income_allocation_rules.percentage` for configuration and `commercial_payment_allocations.percentage` for the historical payment snapshot. |
| INCOME | Calculated as `commercial_payments.amount × allocation percentage / 100`. It is not stored as a second amount. |
| BANK | New tenant-scoped `finance_bank_destinations` plus historical payment allocations. No bank names from the workbook are seeded. |
| SOURCE OF INCOME | New tenant-scoped `finance_income_sources`, referenced by the canonical payment. |
| TOTAL AMOUNT summary | Calculated from canonical payments. |
| BANK / AMOUNT summary | Calculated from payment amount and historical allocation percentage, grouped by configured bank destination. |
| Excel formulas | Replaced by application/database calculations; formulas and workbook sample values are not embedded. |

## Automation behavior

A staff member can select an income source when recording a canonical customer payment. If that source has active allocation rules totaling exactly 100%, AAL copies those percentages to the payment's historical allocation records.

The original payment amount remains authoritative. If the allocation configuration is changed later, previously recorded payments keep the percentage that was effective when they were recorded.

No workbook row, customer/person name, date, amount, bank name, percentage or formula is inserted by migration.

## Refresh/authentication hardening

The frontend now:
- keeps authentication restoration non-blocking for the application shell;
- uses the existing bearer JWT only to bootstrap tenant/role UI state when session context is missing, never for authorization;
- treats only a definitive protected-endpoint `401` as authentication expiry;
- does not clear a valid local session because Render/API wake-up or network validation timed out;
- lets the control tower render a recovery state instead of returning a blank page while authentication is restored.
