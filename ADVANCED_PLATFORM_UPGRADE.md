# LogiCommand Advanced Platform Upgrade — 2026-09-10

This release keeps the existing logistics/TMS/WMS/AAL domain and upgrades the operating experience toward an enterprise freight-forwarding control tower.

## Product benchmark

The platform direction follows capabilities visible in modern freight platforms: unified freight/warehouse workflows, quotes, bookings, customs, documents, accounting, customer portals, carrier connections, and reporting; and control-tower patterns such as multimodal visibility, predictive ETA, exception management, and API-first integration.

## UX changes in this release

- New LogiCommand application shell with persistent navigation.
- Operations Control Tower dashboard.
- Global search affordance.
- KPI cards for active shipments, air movements, revenue, collection and attention items.
- Shipment workload and carrier performance views.
- Advanced shipment filtering and operational ledger.
- AAL Command Center with workbook import and searchable operational records.
- Air Cargo Desk with schedule/capacity search, route intelligence and booking workflow.
- Responsive mobile navigation.
- Professional login/workspace onboarding.
- Consistent design tokens, tables, forms, statuses, cards, alerts and responsive behavior.

## Reliability/security corrections

- Removed duplicate frontend upload implementation that referenced a non-existent browser access-token function.
- Frontend continues to use the HttpOnly session/cookie authentication path and CSRF token flow.
- Booking remains idempotent and uses transactional capacity reservation.

## Product philosophy

No external airline, customs or broker acceptance is fabricated. If a provider is not configured, the UI/API must communicate that honestly.
