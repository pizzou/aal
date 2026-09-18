# LogiCommand — Advanced Client Capture Release

This release keeps the existing logistics platform as the system of record and upgrades the experience toward an enterprise freight-forwarding control tower.

## Product direction

The product is positioned as a unified freight operating system: quote -> book -> execute -> document -> track -> bill -> analyze.

The UX is intentionally designed around operational decisions rather than CRUD screens: active movements, capacity, exceptions, customer commitments, cash exposure, and fast actions.

## Benchmark-informed capabilities

The product structure follows capabilities expected from high-end freight platforms: integrated forwarding, carrier connectivity, bookings, documentation, customs workflows, shipment visibility, multimodal execution, accounting/commercial control, and customer-facing tracking.

External carrier/customs certification is still provider-specific and must be completed with real sandbox/UAT credentials before live claims are made.

## UI upgrades in this release

- Premium responsive LogiCommand shell
- Persistent navigation grouped by Command / Execution / Commercial / Platform
- Global shipment/AWB/client/invoice search
- Operations workspace indicator
- Control Tower dashboard with KPI cards, shipment command board, workload mix, network lanes, attention queue, carrier pulse and fast actions
- Air Cargo decision desk with capacity search, carrier comparison, shipment-linked booking and route recommendations
- Public-facing product landing page designed for client demonstrations and sales conversion
- Consistent typography, spacing, status system, cards, tables and responsive behavior

## Existing platform preserved

TMS, WMS, shipments, fleet, warehouse, AAL Command Center, quotations, invoices, expenses, air cargo, AWB/HAWB, customs, IoT, tracking, rating, reporting, tenant isolation and security hardening remain part of the project.

## Validation note

This environment does not have a complete Maven dependency/toolchain available, and the uploaded frontend node_modules tree is incomplete. The source was modified and inspected, but a clean `mvn clean verify` and `npm ci && npm run build` must be executed in CI/staging before release.
