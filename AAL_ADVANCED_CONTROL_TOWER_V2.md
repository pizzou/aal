# AAL Enterprise Logistics — Advanced Control Tower V2

## Product identity

Africa Aviation Logistic (AAL) is the first operating client. The product is a general multimodal logistics platform, not an aviation-only platform.

## Current V2 control tower

The dashboard is designed around operational decisions rather than generic CRUD reporting.

### Command-center API

`GET /api/command-center/advanced?asOf=YYYY-MM-DD`

Returns:

- operations KPIs
- financial KPIs
- fleet/driver readiness
- transport-mode mix
- shipment status distribution
- seven-day operational/revenue trend
- busiest lanes
- current operational exceptions
- prioritized action queue

### Daily routing API

`GET /api/command-center/daily-operations?date=YYYY-MM-DD`

Existing endpoint retained and used by the dashboard for:

- daily jobs
- routes
- driver assignment
- vehicle assignment
- delays
- unassigned shipments
- fleet readiness

## Multimodal model

The dashboard intentionally treats AIR, ROAD, SEA, RAIL, COURIER, LAST_MILE, RORO and PROJECT_CARGO as transport modes while preserving the existing shipment/transport-leg domain.

## Commercial view

The dashboard exposes shipment-based billed, collected, receivable and operating-cost figures. When more than one shipment currency exists, the dashboard explicitly flags mixed currency and only aggregates the selected display currency rather than silently mixing currencies.

## First-client configuration

The production seed/documentation uses the exact organization name:

**Africa Aviation Logistic**

Default location: Kigali, Rwanda.
Default timezone: Africa/Kigali.
Default display currency: USD until AAL chooses a formal accounting/reporting currency policy.

## Release note

The source package does not contain the AAL daily-routing Excel workbooks. The advanced dashboard therefore uses existing shipment/trip/fleet contracts and deliberately does not invent spreadsheet-specific business rules.
