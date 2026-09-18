# AAL High-End Native Implementation

Implemented from the AAL MOTHERSHIP and AAL Command Center business model without embedding either workbook.

## Added
- Native AAL business engine API.
- Shipment financial calculation endpoint.
- Date/currency-aware operating cockpit.
- Monthly and lane profitability aggregations.
- Native indexes for daily operations and reporting.
- Formal native business-rule documentation.

## Preserved
- Shipment fields from the MOTHERSHIP and Command Center models.
- Quotes, invoices, clients, partners, tasks and expenses.
- Air/road/sea/rail multimodal execution capabilities.
- One-time migration capability for historical client data.

## Removed conceptually from runtime
- Monthly sheets as a data model.
- Spreadsheet formulas as the source of truth.
- Manual calculated fields.
