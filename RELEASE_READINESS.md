# Release Readiness

This repository is a hardened release candidate, not a provider-certified airline/customs production deployment.

## Green internally
- Tenant isolation and RLS
- Authentication lockout, token revocation, HttpOnly browser session and CSRF
- Air capacity row locking
- Idempotent booking/payment/telemetry boundaries
- MAWB validation
- Shipment/booking/invoice concurrency versions
- Excel-compatible AAL operational fields
- WMS/TMS/GPS/shipment tracking
- Air cargo flights, bookings, capacity, load planning, route optimization
- AWB/customs document workflows
- Dynamic pricing engine with deterministic fallback
- Freight invoicing and financial ledger
- Production container configuration

## External UAT required
- Airline schedules/capacity/eBooking
- Cargo-XML/ONE Record/e-AWB
- Customs authority
- Customs broker
- Live flight provider
- IoT devices/gateway
- FX/market pricing feeds

## Mandatory release tests
1. `mvn clean verify`
2. `npm ci && npm run build`
3. Clean PostgreSQL migration from V1 through latest
4. Tenant-isolation integration tests with at least two tenants
5. Concurrent booking/capacity test
6. Concurrent invoice-payment/idempotency test
7. Authentication lockout/revocation test
8. CSRF/browser-cookie test
9. Backup/restore test
10. Load/performance test
11. Provider sandbox UAT and failure/retry tests
12. Security penetration test
13. Monitoring/alerting test
14. Disaster-recovery exercise
