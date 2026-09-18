# AAL Daily Operations V1

## Product direction

The initial production tenant is **Aviation Africa Logistics (AAL)**. The backend retains tenant-aware security and RLS as a future-proof technical boundary, but the initial user experience is AAL-specific and operational rather than a generic SaaS onboarding flow.

## Daily control tower

The `/dashboard` screen is now the AAL Daily Operations Command Center. It is date-driven and combines the existing logistics data model instead of creating a second job system.

The board uses:

- `Shipment` records as the operational jobs.
- `Trip` records as daily routes/dispatch plans.
- `TripShipment` as shipment-to-route assignment.
- `Driver` and `Vehicle` as dispatch resources.
- `ETD`, `ETA`, `nextActionDate`, shipment status and trip status for operational state.

## Daily workflow

`JOB -> ROUTE -> DRIVER/VEHICLE -> DISPATCH -> IN TRANSIT -> DELIVERY`

Operational exceptions are derived for:

- delayed shipments whose ETA has passed and that are not delivered/cancelled;
- pending/in-transit shipments without a route assignment for the selected date.

## API

`GET /api/command-center/daily-operations?date=YYYY-MM-DD`

When `date` is omitted, the current server date is used.

The response contains:

- daily summary;
- fleet readiness;
- routing jobs;
- route summaries;
- operational exceptions.

## Spreadsheet boundary

The current AAL project archive did not contain the company's Excel workbooks. No spreadsheet-specific columns or business rules have been invented. The next workbook import/normalization phase should map the actual AAL workbook fields into the existing Shipment/Trip/Customer/Cargo model after the source workbook is supplied.
