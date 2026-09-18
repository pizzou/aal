# Advanced UI file manifest

Changed:
- frontend/app/layout.tsx — global design shell and metadata
- frontend/app/globals.css — production UI system/responsive styles
- frontend/components/AppShell.tsx — authenticated application navigation/control shell
- frontend/app/page.tsx — application entry point now targets Control Tower
- frontend/app/login/page.tsx — professional responsive login/onboarding experience
- frontend/app/dashboard/page.tsx — operations Control Tower
- frontend/app/shipments/page.tsx — searchable/filterable shipment operations ledger
- frontend/app/command-center/page.tsx — AAL commercial operations workspace
- frontend/app/air-cargo/page.tsx — air cargo search/capacity/booking desk
- frontend/lib/api-client.ts — removed duplicate broken upload implementation

The backend and domain model remain in place; this release primarily turns the existing capabilities into a coherent, high-end operator experience.
