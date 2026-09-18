# AAL implementation manifest

## Spreadsheet source fields

**AAL MOTHERSHIP monthly sheets (January-December):**
DATE | AWB NO | NAME OF CLIENT | CONTACT | COMMODITY | ORIGIN | DESTINATION | GROSS WEIGHT (KG) | VOLUMETRIC WEIGHT (KG) | CHARGEABLE WEIGHT (KG) | SERVICE TYPE | AIRLINE USED | SHIPMENT STATUS | OPERATOR | AMOUNT BILLED TO CLIENT | AMOUNT PAID BY CLIENT | AMOUNT REMAINING | AMOUNT PAID TO SUPPLY | OTHER EXPENSES | NET INCOME | PAYMENT STATUS

**AAL Command Center Shipments:**
Shipment ID | Date Opened | Client | Contact | Service Type | Origin Country | Origin City / Port | Destination Country | Destination City / Port | Commodity | Actual Weight (kg) | Volume Weight (kg) | Chargeable Weight (kg) | Packages | Airline / Carrier | AWB / BL No. | ETD | ETA | Status | Owner | Supplier Cost (USD) | Other Cost (USD) | Total Cost (USD) | Client Revenue (USD) | Gross Profit (USD) | Margin % | Invoice No. | Payment Status | Next Action | Next Action Date | Notes

Also supported: Quotations (17 columns), Invoices (16), Clients (16), Partners (13), Tasks (13), Expenses (15).

## New API areas

- `/api/air-cargo/flights` — persist schedules/capacity signals
- `/api/air-cargo/flights/search` — carrier schedule search
- `/api/air-cargo/capacity` — live/configured capacity lookup
- `/api/air-cargo/bookings` — idempotent eBooking
- `/api/air-cargo/routes/optimize` — direct and one-connection route optimization
- `/api/air-cargo/pieces` — cargo dimensions
- `/api/air-cargo/load-plans` — weight + volume planning
- `/api/air-cargo/documents/awb` — MAWB/HAWB records and validation
- `/api/air-cargo/documents/awb/{id}/submit-to-carrier` — configured carrier submission
- `/api/air-cargo/documents/customs` — customs declarations
- `/api/air-cargo/documents/customs/{id}/submit-to-external` — configured customs/broker submission
- `/api/air-cargo/documents/templates` — standardized templates
- `/api/shipments/{id}/legs` — multimodal transport legs
- `/api/flight-status/shipments/{id}/prediction` — flight-data ETA prediction
- `/api/iot/devices` and `/api/iot/devices/{deviceCode}/readings` — authenticated telemetry
- `/api/rating/dynamic/recommend` — AI-assisted/fallback dynamic pricing
- `/api/billing/shipments/{shipmentId}/invoice` — automated invoice + ledger posting
- `/api/billing/invoices/{invoiceId}/payments` — collection + ledger posting
- `/api/command-center/*` — workbook-equivalent commercial records
- `/api/command-center/import/excel` — `.xlsx`/`.xlsm` import
- `/api/command-center/monthly-summary` — MOTHERSHIP-style monthly summary

External airline/customs calls are intentionally configuration-driven. The platform never fabricates a provider confirmation when the provider endpoint is not configured.
