# AAL final completion package

This release uses the current AAL-FINAL source as the baseline and adds the remaining application-side administration and workspace cleanup requested for the final operating system.

Completed in this pass:
- Canonicalized the internal dashboard to `/aal-control-tower`; `/dashboard` is now a compatibility redirect.
- Removed duplicate `Operations Dashboard` and `Record Shipment` entries from the global internal navigation.
- Made `/shipments` the canonical shipment register and retained `/new-shipment` as the creation workflow reachable from the register.
- Added a tenant-scoped `/settings` workspace covering General, Operations, Notifications, Security, Integrations and Finance settings.
- Added persistent settings storage and tenant RLS in migration V36.
- Kept advanced finance/integration controls under `/platform`, linked from Settings, rather than duplicating them in the main navigation.
- Hardened the admin users workspace with the CUSTOMER role in the client fallback catalogue and exposed customer linkage in the user API response.
- Fixed an existing frontend TypeScript error in the customer shipment portal detail view and corrected the customer portal shipment row type.
- Preserved the previous enterprise completion work: multimodal journeys, customer isolation, notification queue, audit correlation/state capture, MFA provisioning/verification, secure tracking, Kafka GPS publishing, finance controls and production infrastructure baseline.

External provider integrations remain configuration/UAT dependent: airline/carrier, Cargo-XML/ONE Record/e-AWB, customs, GPS/IoT, FX, SMS/WhatsApp, payment and production SMTP credentials must be supplied by AAL or the relevant providers before those external services can operate against live systems.

Validation performed in this environment:
- Frontend TypeScript check: passed with `node node_modules/typescript/bin/tsc --noEmit`.
- Frontend production build could not be completed because the installed Next.js package attempted to download the Linux SWC binary and network/DNS access is unavailable in this environment.
- Backend Maven build is not available in this environment because Maven is not installed.
- Flyway migrations V1 through V36 were checked for unique sequential version numbers.

Before production cutover run:
- `mvn clean verify`
- `npm ci && npm run build`
- Flyway migration validation against a clean PostgreSQL database
- security/dependency scan
- load/concurrency tests
- backup restore/PITR drill
- internal, customer and public E2E acceptance suite
