# AAL Native Operating Model

Aviation Africa Logistics is operated from the application database. The Excel workbooks are historical business-process evidence and optional one-time migration inputs only.

## Native rules
- Chargeable weight = max(gross weight, volumetric weight)
- Total cost = supplier cost + other cost
- Customer receivable = max(billed - collected, 0)
- Gross profit = billed - supplier cost - other cost
- Gross margin % = gross profit / billed * 100
- Mothership net income = billed - amount paid to supplier - other expenses
- Quote amount = (supplier cost + other cost) * (1 + markup %)
- Quote expected profit = quote amount - supplier cost - other cost
- Invoice balance = invoice amount - amount paid
- Invoice aging is derived from due date and balance
- Task overdue = due date < today and status is not Completed/Cancelled
- Client KPIs are derived from shipments and invoices
- Monthly reporting is date grouping, never separate operational tables

## Core workflow
Customer -> Quote -> Booking -> Shipment -> Transport legs -> Milestones -> Customs/Documents -> Delivery -> Invoice -> Collection -> Supplier payment -> Profitability.

Operational status, documentation status, customs status and financial status remain independent.
