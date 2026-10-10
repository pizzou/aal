"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  ManagementReport,
  reportsApi,
  downloadApiFile,
} from "@/lib/api-client";
import ReportCharts from "@/components/ReportCharts";

function localDateInputValue(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
function firstOfMonth() {
  const d = new Date();
  return localDateInputValue(new Date(d.getFullYear(), d.getMonth(), 1));
}
function today() {
  return localDateInputValue(new Date());
}
function money(n: number, c: string) {
  return `${c} ${(n || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function ReportsPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(today());
  const [report, setReport] = useState<ManagementReport | null>(null);
  const [error, setError] = useState("");
  async function downloadReport(format: "pdf" | "xlsx" | "csv") {
    try {
      const params = new URLSearchParams();
      if (from) params.set("from", from);
      if (to) params.set("to", to);
      await downloadApiFile(
        `/api/reports/management/export.${format}?${params.toString()}`,
        `aal-management-report.${format}`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Unable to export report");
    }
  }

  async function load() {
    try {
      setError("");
      setReport(await reportsApi.management(from, to));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Unable to load report");
    }
  }
  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);
  useEffect(() => {
    if (accessToken) load();
  }, [accessToken]);
  if (isLoading || !accessToken) return null;
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">MANAGEMENT REPORTING</div>
          <h1 className="page-title">AAL Management Reports</h1>
          <p className="page-subtitle">
            Daily, monthly, customer, carrier, shipment, revenue, margin,
            receivables and expense reporting from the same controlled data
            model.
          </p>
        </div>
      </div>
      <section className="card">
        <div className="grid grid-3">
          <div className="field">
            <label>From</label>
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </div>
          <div className="field">
            <label>To</label>
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </div>
          <div className="field">
            <label>&nbsp;</label>
            <div className="actions" style={{ flexWrap: "wrap" }}>
              <button className="btn btn-primary" onClick={load}>
                Refresh report
              </button>
              <button className="btn" onClick={() => downloadReport("pdf")}>
                PDF
              </button>
              <button className="btn" onClick={() => downloadReport("xlsx")}>
                Excel
              </button>
              <button className="btn" onClick={() => downloadReport("csv")}>
                CSV
              </button>
            </div>
          </div>
        </div>
      </section>
      {error && (
        <div className="alert alert-error" style={{ marginTop: 14 }}>
          {error}
        </div>
      )}
      {report && (
        <>
          <div className="grid grid-4" style={{ marginTop: 14 }}>
            <div className="card kpi">
              <div className="kpi-label">Revenue</div>
              <div className="kpi-value" style={{ fontSize: 21 }}>
                {money(report.financial.invoicedRevenue, report.currency)}
              </div>
            </div>
            <div className="card kpi">
              <div className="kpi-label">Collections</div>
              <div className="kpi-value" style={{ fontSize: 21 }}>
                {money(report.financial.collectedRevenue, report.currency)}
              </div>
            </div>
            <div className="card kpi">
              <div className="kpi-label">Gross profit</div>
              <div className="kpi-value" style={{ fontSize: 21 }}>
                {money(report.financial.grossProfit, report.currency)}
              </div>
            </div>
            <div className="card kpi">
              <div className="kpi-label">Outstanding</div>
              <div className="kpi-value" style={{ fontSize: 21 }}>
                {money(report.receivables.outstanding, report.currency)}
              </div>
            </div>
          </div>
          <ReportCharts report={report} />
          <section
            className="card aal-monthly-performance"
            style={{ marginTop: 14 }}
          >
            <div className="section-heading">
              <div>
                <div className="eyebrow">MOTHERSHIP REPLACEMENT</div>
                <h2 className="card-title">Monthly performance</h2>
                <p className="page-subtitle">
                  Database-derived equivalent of the workbook Monthly Summary.
                  Legacy MOTHERSHIP billings and collections are included when
                  no corresponding live invoice exists.
                </p>
              </div>
            </div>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Month</th>
                    <th>Shipments</th>
                    <th>Gross kg</th>
                    <th>Chargeable kg</th>
                    <th>Billed</th>
                    <th>Collected</th>
                    <th>Remaining</th>
                    <th>Supplier paid</th>
                    <th>Other expenses</th>
                    <th>Net income</th>
                    <th>Margin</th>
                    <th>Completed</th>
                    <th>Departed</th>
                  </tr>
                </thead>
                <tbody>
                  {report.monthlyTrend.map((item) => (
                    <tr key={item.month}>
                      <td>
                        {new Date(
                          `${item.month}-02T12:00:00`,
                        ).toLocaleDateString(undefined, {
                          month: "short",
                          year: "numeric",
                        })}
                      </td>
                      <td>{item.shipments.toLocaleString()}</td>
                      <td>
                        {item.grossWeightKg.toLocaleString(undefined, {
                          maximumFractionDigits: 3,
                        })}
                      </td>
                      <td>
                        {item.chargeableWeightKg.toLocaleString(undefined, {
                          maximumFractionDigits: 3,
                        })}
                      </td>
                      <td>{money(item.invoicedRevenue, report.currency)}</td>
                      <td>{money(item.collectedRevenue, report.currency)}</td>
                      <td>
                        {money(item.outstandingReceivables, report.currency)}
                      </td>
                      <td>{money(item.supplierPayments, report.currency)}</td>
                      <td>{money(item.otherExpenses, report.currency)}</td>
                      <td>{money(item.netIncome, report.currency)}</td>
                      <td>{item.profitMarginPercent.toFixed(2)}%</td>
                      <td>{item.completed.toLocaleString()}</td>
                      <td>{item.departed.toLocaleString()}</td>
                    </tr>
                  ))}
                  {report.monthlyTrend.length === 0 && (
                    <tr>
                      <td colSpan={13}>
                        No monthly activity exists for the selected period.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
          <section className="grid grid-2" style={{ marginTop: 14 }}>
            <div className="card">
              <h2 className="card-title">Operations</h2>
              <div className="table-wrap">
                <table className="table">
                  <tbody>
                    {[
                      ["Shipments", report.operations.totalShipments],
                      ["Active", report.operations.activeShipments],
                      ["Completed", report.operations.completedShipments],
                      ["Departed", report.operations.departedShipments],
                      ["Delayed", report.operations.delayedShipments],
                      ["Exceptions", report.operations.exceptionShipments],
                      ["Unassigned", report.operations.unassignedShipments],
                      [
                        "On-time rate",
                        `${report.operations.onTimeRatePercent.toFixed(2)}%`,
                      ],
                    ].map((x) => (
                      <tr key={x[0]}>
                        <td>{x[0]}</td>
                        <th>{x[1]}</th>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
            <div className="card">
              <h2 className="card-title">Finance & collections</h2>
              <div className="table-wrap">
                <table className="table">
                  <tbody>
                    {[
                      [
                        "Supplier costs",
                        money(report.financial.supplierCosts, report.currency),
                      ],
                      [
                        "Other costs",
                        money(report.financial.otherCosts, report.currency),
                      ],
                      [
                        "Expenses",
                        money(report.financial.totalExpenses, report.currency),
                      ],
                      [
                        "Gross margin",
                        `${report.financial.grossMarginPercent.toFixed(2)}%`,
                      ],
                      [
                        "Net profit",
                        money(report.financial.netProfit, report.currency),
                      ],
                      [
                        "Overdue",
                        money(report.receivables.overdue, report.currency),
                      ],
                      [
                        "Due today",
                        money(report.receivables.dueToday, report.currency),
                      ],
                      [
                        "Due next 30 days",
                        money(
                          report.receivables.dueNext30Days,
                          report.currency,
                        ),
                      ],
                    ].map((x) => (
                      <tr key={x[0]}>
                        <td>{x[0]}</td>
                        <th>{x[1]}</th>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>
          <section className="grid grid-2" style={{ marginTop: 14 }}>
            <div className="card">
              <h2 className="card-title">Customer profitability</h2>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Customer</th>
                      <th>Shipments</th>
                      <th>Revenue</th>
                      <th>Gross profit</th>
                      <th>Margin</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.customerProfitability.map((x) => (
                      <tr key={x.customer}>
                        <td>{x.customer}</td>
                        <td>{x.shipments}</td>
                        <td>{money(x.revenue, report.currency)}</td>
                        <td>{money(x.grossProfit, report.currency)}</td>
                        <td>{x.marginPercent.toFixed(2)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
            <div className="card">
              <h2 className="card-title">Carrier profitability</h2>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Carrier</th>
                      <th>Shipments</th>
                      <th>Revenue</th>
                      <th>Gross profit</th>
                      <th>Margin</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.carrierProfitability.map((x) => (
                      <tr key={x.carrier}>
                        <td>{x.carrier}</td>
                        <td>{x.shipments}</td>
                        <td>{money(x.revenue, report.currency)}</td>
                        <td>{money(x.grossProfit, report.currency)}</td>
                        <td>{x.marginPercent.toFixed(2)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>
          <section className="grid grid-2" style={{ marginTop: 14 }}>
            <div className="card">
              <h2 className="card-title">Client activity & repeat business</h2>
              <p className="page-subtitle">
                Shipment count and distinct shipment days per client.
              </p>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Client</th>
                      <th>Shipments</th>
                      <th>Days active</th>
                      <th>Last shipment</th>
                      <th>Revenue</th>
                      <th>Outstanding</th>
                      <th>Net income</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.clientActivity.map((x) => (
                      <tr key={x.customer}>
                        <td>{x.customer}</td>
                        <td>{x.shipments}</td>
                        <td>{x.distinctShipmentDays}</td>
                        <td>{x.lastShipmentDate}</td>
                        <td>{money(x.revenue, report.currency)}</td>
                        <td>{money(x.outstanding, report.currency)}</td>
                        <td>{money(x.netIncome, report.currency)}</td>
                      </tr>
                    ))}
                    {report.clientActivity.length === 0 && (
                      <tr>
                        <td colSpan={7}>No client activity for this period.</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
            <div className="card">
              <h2 className="card-title">Destination performance</h2>
              <p className="page-subtitle">
                Shipment volume, chargeable weight, revenue and workbook-style
                net income.
              </p>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Destination</th>
                      <th>Shipments</th>
                      <th>Gross kg</th>
                      <th>Chargeable kg</th>
                      <th>Revenue</th>
                      <th>Net income</th>
                      <th>Margin</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.destinationProfitability.map((x) => (
                      <tr key={x.destination}>
                        <td>{x.destination}</td>
                        <td>{x.shipments}</td>
                        <td>{x.grossWeightKg.toLocaleString()}</td>
                        <td>{x.chargeableWeightKg.toLocaleString()}</td>
                        <td>{money(x.revenue, report.currency)}</td>
                        <td>{money(x.netIncome, report.currency)}</td>
                        <td>{x.marginPercent.toFixed(2)}%</td>
                      </tr>
                    ))}
                    {report.destinationProfitability.length === 0 && (
                      <tr>
                        <td colSpan={7}>
                          No destination data for this period.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </section>
          <section className="grid grid-2" style={{ marginTop: 14 }}>
            <div className="card">
              <h2 className="card-title">Receivables aging</h2>
              {report.receivablesAging.map((x) => (
                <div className="metric-row" key={x.bucket}>
                  <span>
                    {x.bucket}
                    <small>{x.invoiceCount} invoices</small>
                  </span>
                  <strong>{money(x.balance, report.currency)}</strong>
                </div>
              ))}
            </div>
            <div className="card">
              <h2 className="card-title">Quotation pipeline</h2>
              {report.quotationPipeline.map((x) => (
                <div className="metric-row" key={x.status}>
                  <span>
                    {x.status}
                    <small>{x.count} quotations</small>
                  </span>
                  <strong>{money(x.quotedValue, report.currency)}</strong>
                </div>
              ))}
            </div>
          </section>
          <section className="card" style={{ marginTop: 14 }}>
            <h2 className="card-title">Monthly trend</h2>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Month</th>
                    <th>Shipments</th>
                    <th>Gross kg</th>
                    <th>Chargeable kg</th>
                    <th>Completed</th>
                    <th>Departed</th>
                    <th>Revenue</th>
                    <th>Collections</th>
                    <th>Outstanding</th>
                    <th>Supplier payments</th>
                    <th>Other expenses</th>
                    <th>Gross profit</th>
                    <th>Net income</th>
                    <th>Net margin</th>
                  </tr>
                </thead>
                <tbody>
                  {report.monthlyTrend.map((x) => (
                    <tr key={x.month}>
                      <td>{x.month}</td>
                      <td>{x.shipments}</td>
                      <td>{x.grossWeightKg.toLocaleString()}</td>
                      <td>{x.chargeableWeightKg.toLocaleString()}</td>
                      <td>{x.completed}</td>
                      <td>{x.departed}</td>
                      <td>{money(x.invoicedRevenue, report.currency)}</td>
                      <td>{money(x.collectedRevenue, report.currency)}</td>
                      <td>
                        {money(x.outstandingReceivables, report.currency)}
                      </td>
                      <td>{money(x.supplierPayments, report.currency)}</td>
                      <td>{money(x.otherExpenses, report.currency)}</td>
                      <td>{money(x.grossProfit, report.currency)}</td>
                      <td>{money(x.netIncome, report.currency)}</td>
                      <td>{x.profitMarginPercent.toFixed(2)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </main>
  );
}
