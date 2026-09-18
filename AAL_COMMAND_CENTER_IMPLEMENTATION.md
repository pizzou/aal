# AAL Command Center and Air Cargo implementation

This release adds the fields and workflows represented in the supplied `AAL MOTHERSHIP .xlsx` and `AAL_Command_Center.xlsm`.

## Exact operational fields

### MOTHERSHIP
DATE, AWB NO, NAME OF CLIENT, CONTACT, COMMODITY, ORIGIN, DESTINATION, GROSS WEIGHT (KG), VOLUMETRIC WEIGHT (KG), CHARGEABLE WEIGHT (KG), SERVICE TYPE, AIRLINE USED, SHIPMENT STATUS, OPERATOR, AMOUNT BILLED TO CLIENT, AMOUNT PAID BY CLIENT, AMOUNT REMAINING, AMOUNT PAID TO SUPPLY, OTHER EXPENSES, NET INCOME, PAYMENT STATUS.

### AAL Command Center / Shipments
Shipment ID, Date Opened, Client, Contact, Service Type, Origin Country, Origin City / Port, Destination Country, Destination City / Port, Commodity, Actual Weight (kg), Volume Weight (kg), Chargeable Weight (kg), Packages, Airline / Carrier, AWB / BL No., ETD, ETA, Status, Owner, Supplier Cost (USD), Other Cost (USD), Total Cost (USD), Client Revenue (USD), Gross Profit (USD), Margin %, Invoice No., Payment Status, Next Action, Next Action Date, Notes.

The server calculates chargeable weight, total cost, gross profit, margin, amount remaining and MOTHERSHIP-style net income instead of trusting client-supplied calculated values.

## Additional workbook entities

Quotations, Invoices, Clients, Partners, Tasks and Expenses have dedicated persisted records matching the supplied workbook columns.

## New capabilities

- configurable direct carrier schedule/capacity/booking gateway
- persisted flight capacity and idempotent eBooking
- one-stop TMS-facing booking API
- AI-assisted dynamic spot-price recommendation when an AI API key is configured, with deterministic capacity/urgency fallback
- MAWB/HAWB e-AWB record, validation and external carrier submission endpoint
- customs declaration and broker/customs external submission gateway
- standardized document template repository
- multi-modal transport legs
- air-cargo pieces with dimensions, volume, stackability and temperature-control flags
- air load planning by both weight and volume
- direct and one-connection flight route optimization using persisted flight data
- flight-status-driven predicted ETA
- authenticated IoT device ingestion for shipment temperature/humidity telemetry
- automated freight invoicing and double-entry revenue/receivable/cash ledger records
- Excel `.xlsx` / `.xlsm` import for MOTHERSHIP monthly sheets and Command Center workbook sheets
- Command Center UI and Air Cargo UI

## External integration truthfulness

The application does not manufacture airline or customs confirmations. If the relevant endpoint/credentials are not configured, the system does not claim that a carrier booking or customs submission occurred. Configure:

AIRCARGO_CARRIER_BASE_URL
AIRCARGO_CARRIER_API_KEY
AIRCARGO_CUSTOMS_BASE_URL
AIRCARGO_CUSTOMS_BROKER_URL
AIRCARGO_INTEGRATION_API_KEY
PRICING_AI_ENABLED
PRICING_AI_API_KEY
PRICING_AI_MODEL

A real airline, Cargo-XML/ONE Record provider, customs authority or broker contract still determines the exact production payload, authentication and certification requirements.
