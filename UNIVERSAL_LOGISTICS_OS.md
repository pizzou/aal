# LogiCommand — Universal Global Logistics OS

This release evolves the platform from an air-cargo-oriented system into a **mode-neutral international logistics operating system**.

## Operating model

Every shipment is a single commercial/operational record. It can contain multiple transport legs and multiple cargo lines, allowing combinations such as:

`Supplier → Pickup (Road) → Warehouse → Port → Ocean (FCL/LCL) → Rail → Cross-dock → Last Mile → POD`

The same model supports air, ocean, road, rail, inland waterway, courier/parcel, last-mile, RoRo and project cargo.

## Implemented in this release

- Expanded `TransportMode` for global multimodal operations.
- Universal cargo-item model: packages, weight, volume, dimensions, HS code, origin, value/currency, dangerous goods and temperature controls.
- Universal transport-plan legs with mode, carrier, locations, planned/actual times and equipment.
- Ocean foundation: voyages, vessel/IMO, schedules, FCL/LCL booking types, containers, seals and VGM controls.
- Road foundation: CMR, vehicle, trailer, driver and delivery execution.
- Rail foundation: consignment, train, wagons and terminal flow.
- Universal document room model for AWB/HAWB, MBL/HBL, CMR, VGM, commercial and customs documents.
- Universal rate foundation for base freight, fuel/security, handling, customs and accessorial costs.
- Exception-management foundation with severity, ownership, due time and resolution state.
- Tenant-isolated database tables with RLS and supporting indexes.
- Control-tower metrics for active shipments, ocean bookings, planned legs, cold-chain and dangerous-goods workload.
- Premium frontend navigation and a guided New Shipment wizard.
- Dedicated Ocean, Road, Rail and Documents operational workspaces.

## Enterprise roadmap already aligned with the architecture

The data model is intentionally ready to add provider adapters for carrier schedules/rates, customs, tracking, maps, telematics, payment providers and customer portals without changing the core shipment model.

Production certification still requires CI execution (`mvn clean verify`, `npm ci && npm run build`), integration tests against PostgreSQL/Redis, external-provider contract tests, load tests, security testing, backup/restore drills and staging acceptance.
