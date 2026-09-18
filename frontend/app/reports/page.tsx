"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError, ManagementReport, reportsApi } from "@/lib/api-client";

function firstOfMonth() {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10);
}
function today() {
  return new Date().toISOString().slice(0, 10);
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
            <button className="btn btn-primary" onClick={load}>
              Refresh report
            </button>
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
                    <th>Revenue</th>
                    <th>Collections</th>
                    <th>Outstanding</th>
                    <th>Gross profit</th>
                  </tr>
                </thead>
                <tbody>
                  {report.monthlyTrend.map((x) => (
                    <tr key={x.month}>
                      <td>{x.month}</td>
                      <td>{x.shipments}</td>
                      <td>{money(x.invoicedRevenue, report.currency)}</td>
                      <td>{money(x.collectedRevenue, report.currency)}</td>
                      <td>
                        {money(x.outstandingReceivables, report.currency)}
                      </td>
                      <td>{money(x.grossProfit, report.currency)}</td>
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
