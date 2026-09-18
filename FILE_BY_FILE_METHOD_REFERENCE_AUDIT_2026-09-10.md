# AAL Enterprise Phase 1 — File-by-File Method Reference Audit

Date: 2026-09-10

## Scope

Reviewed the extracted AAL Enterprise Phase 1 clean-architecture backend source tree:

- 192 Java source files under `backend/src/main/java/com/logiplatform`
- Controllers
- DTOs/records
- Models/entities/enums
- Repositories
- Services
- Security
- Configuration
- Tenancy/common infrastructure
- V22 enterprise migration and related AAL bootstrap files

The review specifically looked for calls where code references a method/accessor that is not declared on the target type, with special attention to the newly added AAL Enterprise Phase 1 code.

## Definite method-reference problems found and corrected

### 1. `AuthService.register()` / `LoginRequest`

The previous source had an invalid `request.tenantName()` call in the login flow. `AuthDtos.LoginRequest` contains only:

- `email`
- `password`

`tenantName` belongs to `RegisterRequest` only.

Correction: tenant name handling is retained only inside `register(RegisterRequest)` and is no longer referenced by `login(LoginRequest)`.

### 2. `AalExcelImportService` / `ShipmentRepository`

`AalExcelImportService.importShipments()` called:

`shipments.findByTenantIdAndReferenceCode(tenantId, referenceCode)`

but `ShipmentRepository` previously exposed only:

`existsByTenantIdAndReferenceCode(...)`

Correction: added the exact repository method:

`Optional<Shipment> findByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);`

This matches the existing service call and preserves tenant scoping.

## Existing method references verified

The review also checked the main cross-layer calls used by the platform, including:

- ShipmentService -> ShipmentRepository
- BillingService -> ShipmentRepository / CommercialInvoiceRepository / CommercialPaymentRepository / FinanceLedgerRepository
- AirCargoBookingService -> AirCargoBookingRepository / AirCargoFlightRepository / ShipmentService / ExternalGatewayService
- AirCargoConnectivityService -> AirCargoFlightRepository / ExternalGatewayService
- AirCargoDocumentService -> AWB/customs/document repositories and model state methods
- SensorMonitoringService -> ShipmentService / sensor repositories / Shipment model tracking integration
- StockMovementService -> InventoryItemRepository and InventoryItem mutation methods
- TripService -> VehicleService / DriverService / ShipmentService / trip repositories
- InventoryService -> WarehouseService / InventoryItemRepository
- WarehouseService -> WarehouseRepository
- DriverService -> DriverRepository
- VehicleService -> VehicleRepository
- RouteOptimizationService -> AirCargoFlightRepository and AirCargoFlight model accessors
- PredictiveMilestoneService -> ShipmentRepository / FlightStatusPort
- CommandCenter services/controllers -> existing DTO and repository contracts
- Universal logistics controllers -> CargoItem / TransportPlanLeg repositories
- Mode operations -> Ocean/Road/Rail repositories

Spring Data inherited methods such as `save`, `saveAndFlush`, `findById`, `findAll`, `count`, and delete methods were treated as valid because the repositories extend `JpaRepository`.

Java record accessors such as `request.email()`, `request.password()`, `request.quoteNumber()`, etc. were treated as generated accessors and checked against their record components.

## Important limitation

This is a source-level reference audit. The current environment does not contain Maven or the project's downloaded Maven dependencies, so a real `mvn clean verify` could not be executed here.

Therefore this audit does NOT claim a successful Java compilation. The final validation must still run:

```bash
mvn clean verify
```

in CI/deployment with the project's dependency graph available.

## Architecture expectation going forward

New code must follow:

`controller -> service -> repository/model`

for business operations whenever a business workflow is involved.

DTOs remain API contracts, repositories remain persistence interfaces, services own business workflows, and models own entity/domain state.

No new feature should invent a method call merely because a similarly named method exists elsewhere. Before adding a call, the target type's actual public/package-visible contract must be checked.
