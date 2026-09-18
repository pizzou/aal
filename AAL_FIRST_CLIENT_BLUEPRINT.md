# Africa Aviation Logistic — First Production Client Blueprint

## Tenant
Use **Africa Aviation Logistic** as the first tenant/workspace when provisioning the production environment.

## Operating model
AAL should be able to manage, from one workspace:
- Air freight and air cargo
- Road pickup/delivery and cross-border trucking
- Ocean freight when required by customers
- Warehouse and cross-dock operations
- Customs/document workflows
- Customer quotations and bookings
- Shipment profitability and receivables
- Exceptions, tasks and SLA timers
- Customer tracking and document sharing

## Initial master data to configure
- Company legal/trading name
- Rwanda base location
- Customer contacts and billing contacts
- Airports, ports, border points and warehouses used by AAL
- Airlines, shipping lines, road carriers and other partners
- AAL service catalogue
- AAL selling tariffs and supplier costs
- Default currency and supported transaction currencies
- Payment terms
- Document templates and numbering sequences

## First operational workflow
Customer request → Quote → Approval → Booking → Shipment → Cargo → Transport plan → Documents/Customs → Execution → Delivery/POD → Invoice → Payment → Profitability → Customer notification.

## AAL migration
The existing AAL Command Center workbook remains the migration source for historical operational data. Import must be validated before posting financial or customer-master records into production.

## Production rule
Do not put passwords, API keys, carrier credentials or customer secrets into this blueprint or database migrations. Provision them through deployment secrets.
