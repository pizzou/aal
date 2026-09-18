# Production File-by-File Audit — Logistics Platform

Date: 2026-09-10

## Scope
Inspected every Java source file under `backend/src/main/java`, every backend test source,
every TypeScript/TSX source under `frontend`, Flyway migrations, application configuration,
Docker/Compose files, and the AAL implementation documentation.

Source inventory:
- Java production sources: 169
- Java tests: 13
- TypeScript/TSX: 17
- Flyway migrations after hardening: 20

## Implemented production hardening
1. All tenant-owned tables have PostgreSQL RLS/forced RLS.
2. Pooled tenant context is explicitly cleared on every connection checkout.
3. JWT token versions permit immediate revocation.
4. Login failure lockout: 5 failures / 15-minute lock.
5. Browser authentication uses an HttpOnly `NLS_SESSION` cookie; CSRF protection uses `NLS_CSRF` + `X-CSRF-Token`.
6. Authentication logout increments token version.
7. Air-flight capacity reservation uses pessimistic row locking.
8. Shipment, booking, invoice and flight mutations use optimistic concurrency versions where appropriate.
9. Commercial payments have durable idempotency keys.
10. MAWB validation includes the IATA 11-digit check digit.
11. AWB/customs local preparation no longer fabricates external acceptance.
12. Carrier schedule/capacity integration maps canonical provider records rather than deserializing JPA entities.
13. External booking/AWB/customs calls send idempotency keys.
14. IoT readings support event IDs and reject stale/future telemetry.
15. Large vehicle load plans use a bounded deterministic fallback instead of an unimplemented failure.
16. Financial invoice status/aging fields are exposed from server-side calculations.
17. Conflict and malformed-parameter exceptions are mapped to appropriate HTTP responses.
18. Production profile validates strong JWT/database/CORS configuration.
19. Backend and frontend have non-root production container images.
20. Production Compose includes a separate least-privilege application DB role bootstrap.
21. Frontend has global loading/error boundaries and no longer exposes the legacy mock booking API.

## AAL spreadsheet compatibility
The shipment model retains the MOTHERSHIP fields:
DATE, AWB NO, NAME OF CLIENT, CONTACT, COMMODITY, ORIGIN, DESTINATION,
GROSS WEIGHT, VOLUMETRIC WEIGHT, CHARGEABLE WEIGHT, SERVICE TYPE, AIRLINE USED,
SHIPMENT STATUS, OPERATOR, AMOUNT BILLED TO CLIENT, AMOUNT PAID BY CLIENT,
AMOUNT REMAINING, AMOUNT PAID TO SUPPLY, OTHER EXPENSES, NET INCOME, PAYMENT STATUS.

The Command Center models/importer retain the workbook operational fields for:
Shipments, Quotations, Invoices, Clients, Partners, Tasks and Expenses.

## External integration boundary
The following cannot be declared operational without real provider contracts, credentials,
certification and end-to-end acknowledgement testing:
- airline live schedule/capacity/eBooking
- IATA/Cargo-XML/ONE Record submission
- customs authority submission
- customs broker integration
- production IoT device network
- production market-rate feed

The application now has explicit adapter boundaries and truthful status handling for these.

## Validation performed in this environment
- YAML configuration parsing: PASS.
- Migration version uniqueness: PASS after removing duplicate V16.
- Java source scan for accidental literal `\\n`/escaped underscore corruption: PASS.
- Source-level javac invocation was attempted, but this environment does not contain Maven/dependency
  jars, so a complete dependency-aware compilation could not be performed here.
- Frontend dependency installation timed out and the local `next` binary was unavailable, so a full
  Next.js production build could not be performed here.

## Required final release gate
Run from `backend`:
`mvn clean verify`

Run from `frontend`:
`npm ci && npm run build`

Then test against PostgreSQL + Redis using the production profile and execute provider sandbox/UAT
flows before live activation.

## File inventory reviewed

### Backend production Java

- `backend/src/main/java/com/logiplatform/LogiPlatformApplication.java`
- `backend/src/main/java/com/logiplatform/common/GlobalExceptionHandler.java`
- `backend/src/main/java/com/logiplatform/common/RequestCorrelationFilter.java`
- `backend/src/main/java/com/logiplatform/config/AuthDataConfig.java`
- `backend/src/main/java/com/logiplatform/config/JpaTenantConfig.java`
- `backend/src/main/java/com/logiplatform/config/ProductionConfigurationValidator.java`
- `backend/src/main/java/com/logiplatform/config/ReportingConfig.java`
- `backend/src/main/java/com/logiplatform/config/RestClientConfig.java`
- `backend/src/main/java/com/logiplatform/config/SecurityConfig.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoBooking.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoBookingRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoBookingService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoConnectivityService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoController.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoDocumentController.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoDocumentService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoDtos.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoFlight.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirCargoFlightRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirLoadPlanningController.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AirLoadPlanningService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AwbRecord.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/AwbRecordRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CargoDocument.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CargoDocumentRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CargoPiece.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CargoPieceRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CustomsDeclaration.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/CustomsDeclarationRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/DocumentTemplate.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/DocumentTemplateRepository.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/ExternalGatewayService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/RouteOptimizationService.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/TransportLeg.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/TransportLegController.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/TransportLegDtos.java`
- `backend/src/main/java/com/logiplatform/domain/aircargo/TransportLegRepository.java`
- `backend/src/main/java/com/logiplatform/domain/auth/AuthController.java`
- `backend/src/main/java/com/logiplatform/domain/auth/AuthDtos.java`
- `backend/src/main/java/com/logiplatform/domain/auth/AuthService.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/AalExcelImportController.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/AalExcelImportService.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/BillingController.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/BillingService.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/ClientRecord.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/ClientRecordRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommandCenterController.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommandCenterDtos.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommandCenterService.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialInvoice.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialInvoiceRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialPayment.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialPaymentRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialQuote.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/CommercialQuoteRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/ExpenseRecord.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/ExpenseRecordRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/FinanceLedgerEntry.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/FinanceLedgerRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/MonthlySummaryController.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/MonthlySummaryService.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/PartnerRecord.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/PartnerRecordRepository.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/TaskRecord.java`
- `backend/src/main/java/com/logiplatform/domain/commercial/TaskRecordRepository.java`
- `backend/src/main/java/com/logiplatform/domain/documents/AwbDocumentService.java`
- `backend/src/main/java/com/logiplatform/domain/documents/DocumentController.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/AviationStackFlightStatusAdapter.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/FlightDelayCheckService.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/FlightStatusController.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/FlightStatusPort.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/NoOpFlightStatusAdapter.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/PredictiveMilestoneController.java`
- `backend/src/main/java/com/logiplatform/domain/flightstatus/PredictiveMilestoneService.java`
- `backend/src/main/java/com/logiplatform/domain/loadplanning/LoadPlanDtos.java`
- `backend/src/main/java/com/logiplatform/domain/loadplanning/LoadPlanningController.java`
- `backend/src/main/java/com/logiplatform/domain/loadplanning/LoadPlanningService.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/LoggingNotificationAdapter.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/Notification.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/NotificationRepository.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/NotificationResponse.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/NotificationSenderPort.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/NotificationService.java`
- `backend/src/main/java/com/logiplatform/domain/notifications/SmtpNotificationAdapter.java`
- `backend/src/main/java/com/logiplatform/domain/publictracking/PublicTrackingController.java`
- `backend/src/main/java/com/logiplatform/domain/publictracking/PublicTrackingDtos.java`
- `backend/src/main/java/com/logiplatform/domain/publictracking/PublicTrackingService.java`
- `backend/src/main/java/com/logiplatform/domain/rating/AccessorialCharge.java`
- `backend/src/main/java/com/logiplatform/domain/rating/AccessorialChargeRepository.java`
- `backend/src/main/java/com/logiplatform/domain/rating/DynamicPricingController.java`
- `backend/src/main/java/com/logiplatform/domain/rating/DynamicPricingService.java`
- `backend/src/main/java/com/logiplatform/domain/rating/RateCard.java`
- `backend/src/main/java/com/logiplatform/domain/rating/RateCardRepository.java`
- `backend/src/main/java/com/logiplatform/domain/rating/RateEngineService.java`
- `backend/src/main/java/com/logiplatform/domain/rating/RatingController.java`
- `backend/src/main/java/com/logiplatform/domain/rating/RatingDtos.java`
- `backend/src/main/java/com/logiplatform/domain/reporting/ReportingController.java`
- `backend/src/main/java/com/logiplatform/domain/reporting/ReportingDtos.java`
- `backend/src/main/java/com/logiplatform/domain/reporting/ReportingService.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/IoTDevice.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/IoTDeviceController.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/IoTDeviceRepository.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/IoTDeviceService.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorDtos.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorMonitoringController.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorMonitoringService.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorReading.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorReadingRepository.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorThreshold.java`
- `backend/src/main/java/com/logiplatform/domain/sensors/SensorThresholdRepository.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/CommandCenterShipmentController.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/CommandCenterShipmentDtos.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/Shipment.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentController.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentDtos.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentRepository.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentService.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentStatus.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentTrackingEvent.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/ShipmentTrackingEventRepository.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/TrackingEventType.java`
- `backend/src/main/java/com/logiplatform/domain/shipment/TransportMode.java`
- `backend/src/main/java/com/logiplatform/domain/tenant/Tenant.java`
- `backend/src/main/java/com/logiplatform/domain/tms/Driver.java`
- `backend/src/main/java/com/logiplatform/domain/tms/DriverController.java`
- `backend/src/main/java/com/logiplatform/domain/tms/DriverRepository.java`
- `backend/src/main/java/com/logiplatform/domain/tms/DriverService.java`
- `backend/src/main/java/com/logiplatform/domain/tms/DriverStatus.java`
- `backend/src/main/java/com/logiplatform/domain/tms/GpsDtos.java`
- `backend/src/main/java/com/logiplatform/domain/tms/GpsTrackingController.java`
- `backend/src/main/java/com/logiplatform/domain/tms/GpsTrackingService.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TmsDtos.java`
- `backend/src/main/java/com/logiplatform/domain/tms/Trip.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripController.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripRepository.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripService.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripShipment.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripShipmentId.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripShipmentRepository.java`
- `backend/src/main/java/com/logiplatform/domain/tms/TripStatus.java`
- `backend/src/main/java/com/logiplatform/domain/tms/Vehicle.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleController.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleGpsPosition.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleGpsPositionRepository.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleRepository.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleService.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleStatus.java`
- `backend/src/main/java/com/logiplatform/domain/tms/VehicleType.java`
- `backend/src/main/java/com/logiplatform/domain/user/User.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/InventoryController.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/InventoryItem.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/InventoryItemRepository.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/InventoryService.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/MovementType.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/StockMovement.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/StockMovementRepository.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/StockMovementService.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/Warehouse.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/WarehouseController.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/WarehouseDtos.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/WarehouseRepository.java`
- `backend/src/main/java/com/logiplatform/domain/warehouse/WarehouseService.java`
- `backend/src/main/java/com/logiplatform/security/AuthRateLimitFilter.java`
- `backend/src/main/java/com/logiplatform/security/BrowserCsrfFilter.java`
- `backend/src/main/java/com/logiplatform/security/JwtAuthenticationFilter.java`
- `backend/src/main/java/com/logiplatform/security/JwtService.java`
- `backend/src/main/java/com/logiplatform/security/TenantPrincipal.java`
- `backend/src/main/java/com/logiplatform/tenancy/TenantAwareDataSource.java`
- `backend/src/main/java/com/logiplatform/tenancy/TenantContext.java`

### Backend tests

- `backend/src/test/java/com/logiplatform/domain/aircargo/AirCargoFlightTest.java`
- `backend/src/test/java/com/logiplatform/domain/aircargo/AwbRecordValidationTest.java`
- `backend/src/test/java/com/logiplatform/domain/flightstatus/FlightStatusTest.java`
- `backend/src/test/java/com/logiplatform/domain/loadplanning/LoadPlanningTest.java`
- `backend/src/test/java/com/logiplatform/domain/notifications/NotificationTest.java`
- `backend/src/test/java/com/logiplatform/domain/publictracking/PublicTrackingTest.java`
- `backend/src/test/java/com/logiplatform/domain/rating/RateEngineTest.java`
- `backend/src/test/java/com/logiplatform/domain/reporting/ReportingTest.java`
- `backend/src/test/java/com/logiplatform/domain/sensors/SensorMonitoringTest.java`
- `backend/src/test/java/com/logiplatform/domain/shipment/ShipmentTenantIsolationTest.java`
- `backend/src/test/java/com/logiplatform/domain/tms/GpsTrackingTest.java`
- `backend/src/test/java/com/logiplatform/domain/tms/TripDispatchTest.java`
- `backend/src/test/java/com/logiplatform/domain/warehouse/WarehouseInventoryTenantIsolationTest.java`

### Frontend TypeScript/TSX

- `frontend/app/air-cargo/page.tsx`
- `frontend/app/booking-demo/page.tsx`
- `frontend/app/command-center/page.tsx`
- `frontend/app/dashboard/page.tsx`
- `frontend/app/inventory/[id]/page.tsx`
- `frontend/app/layout.tsx`
- `frontend/app/login/page.tsx`
- `frontend/app/page.tsx`
- `frontend/app/shipments/[id]/page.tsx`
- `frontend/app/shipments/page.tsx`
- `frontend/app/track/[token]/page.tsx`
- `frontend/app/trips/page.tsx`
- `frontend/app/warehouses/[id]/page.tsx`
- `frontend/app/warehouses/page.tsx`
- `frontend/lib/api-client.ts`
- `frontend/lib/auth-context.tsx`
- `frontend/next-env.d.ts`