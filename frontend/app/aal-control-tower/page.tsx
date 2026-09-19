"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  AdvancedDashboard,
  ApiError,
  commandCenterApi,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";

const today = () => new Date().toLocaleDateString("en-CA");

function money(value: number | null | undefined, currency: string): string {
  return `${currency} ${(value ?? 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function pct(value: number | null | undefined): string {
  return `${(value ?? 0).toFixed(1)}%`;
}

function severityClass(value: string): string {
  const normalized = value.toUpperCase();
  if (normalized === "CRITICAL") return "status status-danger";
  if (normalized === "HIGH") return "status status-warning";
  return "status status-neutral";
}

export default function AalControlTower() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [data, setData] = useState<AdvancedDashboard | null>(null);
  const [asOf, setAsOf] = useState(today());
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);

  async function load() {
    if (!accessToken) return;
    setRefreshing(true);
    setError("");
    try {
      setData(await commandCenterApi.advanced(asOf));
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to load control tower",
      );
    } finally {
      setRefreshing(false);
    }
  }

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (accessToken) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken, asOf]);

  if (isLoading || !accessToken) return null;

  const currency = data?.financial.currency ?? "USD";

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / OPERATIONS CONTROL TOWER</div>
          <h1 className="page-title">Daily operations command center</h1>
          <p className="page-subtitle">
            Run the logistics business from one screen: shipments, today's
            workload, exceptions, fleet readiness, cash exposure and commercial
            priorities.
          </p>
        </div>
        <div className="actions">
          <label className="card-muted">
            As of
            <input
              className="input"
              type="date"
              value={asOf}
              onChange={(e) => setAsOf(e.target.value)}
            />
          </label>
          <button
            className="btn"
            type="button"
            onClick={load}
            disabled={refreshing}
          >
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
          <Link className="btn btn-primary" href="/new-shipment">
            + New shipment
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      {!data ? (
        <div className="card">Loading live control-tower data…</div>
      ) : (
        <>
          <section className="grid grid-4">
            <Kpi
              title="Active shipments"
              value={data.operations.activeShipments.toLocaleString()}
              meta={`${data.operations.totalShipments} total`}
              href="/shipments"
            />
            <Kpi
              title="Due today"
              value={data.operations.dueToday.toLocaleString()}
              meta={`${data.operations.unassignedShipments} unassigned`}
              href="/shipments"
            />
            <Kpi
              title="Delayed / exceptions"
              value={`${data.operations.delayedShipments} / ${data.operations.exceptionShipments}`}
              meta="Operational attention required"
              href="/exceptions"
            />
            <Kpi
              title="Open tasks"
              value={data.operatingKpis.openTasks.toLocaleString()}
              meta={`${data.operatingKpis.overdueTasks} overdue`}
              href="/commercial"
            />
          </section>

          <section className="grid grid-4" style={{ marginTop: 16 }}>
            <Kpi
              title="Receivables"
              value={money(data.financial.receivables, currency)}
              meta={`${money(data.operatingKpis.overdueReceivables, currency)} overdue`}
              href="/billing"
            />
            <Kpi
              title="Revenue invoiced"
              value={money(data.operatingKpis.revenueInvoiced, currency)}
              meta={`${money(data.financial.collected, currency)} collected`}
              href="/billing"
            />
            <Kpi
              title="Gross profit"
              value={money(data.operatingKpis.grossProfit, currency)}
              meta={`${pct(data.operatingKpis.overallProfitMargin)} margin`}
              href="/reports"
            />
            <Kpi
              title="Sales pipeline"
              value={data.operatingKpis.openQuotations.toLocaleString()}
              meta={`${data.operatingKpis.wonQuotations} won · ${pct(data.operatingKpis.salesWinRate)} win rate`}
              href="/commercial"
            />
          </section>

          <section className="grid grid-2" style={{ marginTop: 16 }}>
            <div className="card">
              <div className="page-head compact">
                <div>
                  <div className="eyebrow">NEXT ACTIONS</div>
                  <h2 className="card-title">What the team should work on</h2>
                </div>
              </div>
              <div className="stack">
                {data.actions.length ? (
                  data.actions.map((action, index) => (
                    <Link
                      className="action-row"
                      href={action.href}
                      key={`${action.title}-${index}`}
                    >
                      <span className={severityClass(action.priority)}>
                        {action.priority}
                      </span>
                      <span>
                        <strong>{action.title}</strong>
                        <small>{action.detail}</small>
                      </span>
                      <span aria-hidden>→</span>
                    </Link>
                  ))
                ) : (
                  <div className="empty">
                    No priority actions require attention.
                  </div>
                )}
              </div>
            </div>

            <div className="card">
              <div className="eyebrow">EXCEPTION BOARD</div>
              <h2 className="card-title">Operational attention</h2>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Severity</th>
                      <th>Shipment</th>
                      <th>Issue</th>
                      <th>Lane</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.exceptions.slice(0, 8).map((item, index) => (
                      <tr key={`${item.reference}-${index}`}>
                        <td>
                          <span className={severityClass(item.severity)}>
                            {item.severity}
                          </span>
                        </td>
                        <td>
                          <Link
                            className="table-link strong-link"
                            href={`/shipments?q=${encodeURIComponent(item.reference)}`}
                          >
                            {item.reference}
                          </Link>
                        </td>
                        <td>{item.message}</td>
                        <td>{item.lane}</td>
                      </tr>
                    ))}
                    {!data.exceptions.length && (
                      <tr>
                        <td colSpan={4} className="empty">
                          No open operational exceptions.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <section className="grid grid-3" style={{ marginTop: 16 }}>
            <div className="card">
              <div className="eyebrow">FLEET READINESS</div>
              <h2 className="card-title">Vehicles & drivers</h2>
              <MetricRow
                label="Vehicles available"
                value={`${data.fleet.availableVehicles} / ${data.fleet.totalVehicles}`}
                percent={data.fleet.vehicleUtilizationPercent}
              />
              <MetricRow
                label="Vehicles on trip"
                value={String(data.fleet.onTripVehicles)}
              />
              <MetricRow
                label="Vehicles maintenance"
                value={String(data.fleet.maintenanceVehicles)}
              />
              <MetricRow
                label="Drivers available"
                value={`${data.fleet.availableDrivers} / ${data.fleet.totalDrivers}`}
                percent={data.fleet.driverUtilizationPercent}
              />
              <MetricRow
                label="Drivers on trip"
                value={String(data.fleet.onTripDrivers)}
              />
            </div>

            <div className="card">
              <div className="eyebrow">RECEIVABLES AGING</div>
              <h2 className="card-title">Collection risk</h2>
              {data.receivablesAging.map((item) => (
                <MetricRow
                  key={item.bucket}
                  label={item.bucket}
                  value={money(item.balance, currency)}
                />
              ))}
            </div>

            <div className="card">
              <div className="eyebrow">SALES FUNNEL</div>
              <h2 className="card-title">Quotation status</h2>
              {data.quotationStatus.map((item) => (
                <MetricRow
                  key={item.status}
                  label={item.status || "Unspecified"}
                  value={item.count.toLocaleString()}
                />
              ))}
              <div className="card-muted" style={{ marginTop: 12 }}>
                {data.operatingKpis.overdueTasks} overdue tasks require
                follow-up.
              </div>
            </div>
          </section>

          <section className="grid grid-2" style={{ marginTop: 16 }}>
            <div className="card">
              <div className="eyebrow">LANE & MODE INTELLIGENCE</div>
              <h2 className="card-title">Network mix</h2>
              <div className="grid grid-2">
                <div>
                  <h3 className="card-muted">Transport modes</h3>
                  {data.modeMix.map((item) => (
                    <MetricRow
                      key={item.mode}
                      label={item.mode}
                      value={`${item.shipments} · ${pct(item.sharePercent)}`}
                    />
                  ))}
                </div>
                <div>
                  <h3 className="card-muted">Top lanes</h3>
                  {data.topLanes.map((item) => (
                    <MetricRow
                      key={item.lane}
                      label={item.lane}
                      value={`${item.shipments} · ${item.delayedShipments} delayed`}
                    />
                  ))}
                </div>
              </div>
            </div>

            <div className="card">
              <div className="eyebrow">7-DAY TREND</div>
              <h2 className="card-title">Revenue vs operating cost</h2>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Shipments</th>
                      <th>Revenue</th>
                      <th>Operating cost</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.trend.map((item) => (
                      <tr key={item.date}>
                        <td>{item.date}</td>
                        <td>{item.shipments}</td>
                        <td>{money(item.revenue, currency)}</td>
                        <td>{money(item.operatingCost, currency)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <section className="card" style={{ marginTop: 16 }}>
            <div className="eyebrow">FINANCIAL TREND</div>
            <h2 className="card-title">Monthly commercial performance</h2>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Month</th>
                    <th>Revenue</th>
                    <th>Gross profit</th>
                    <th>Margin</th>
                  </tr>
                </thead>
                <tbody>
                  {data.monthlyFinancial.map((item) => (
                    <tr key={item.month}>
                      <td>
                        <strong>{item.month}</strong>
                      </td>
                      <td>{money(item.revenue, currency)}</td>
                      <td>{money(item.grossProfit, currency)}</td>
                      <td>
                        {item.revenue
                          ? pct((item.grossProfit / item.revenue) * 100)
                          : "0.0%"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="card" style={{ marginTop: 16 }}>
            <div className="eyebrow">WORKBOOK PARITY</div>
            <h2 className="card-title">
              The manual workflow is now one controlled operating system
            </h2>
            <div className="grid grid-4">
              <Rule
                title="MOTHERSHIP"
                detail="AWB, client, contact, commodity, route, weight, airline, status, operator, billed, collected, supplier paid, expenses and net income."
                href="/shipments"
              />
              <Rule
                title="COMMAND CENTER"
                detail="Shipments, quotations, invoices, clients, partners, tasks and expenses are persisted and searchable."
                href="/commercial"
              />
              <Rule
                title="FINANCE"
                detail="Receivables, aging, payments, supplier cash and reconciliation are server-controlled and auditable."
                href="/billing"
              />
              <Rule
                title="EXECUTION"
                detail="Trips, drivers, vehicles, tracking, exceptions and multimodal legs extend the workbook beyond manual rows."
                href="/dispatch"
              />
            </div>
          </section>
        </>
      )}
    </main>
  );
}

function Kpi({
  title,
  value,
  meta,
  href,
}: {
  title: string;
  value: string;
  meta: string;
  href: string;
}) {
  return (
    <Link className="card kpi premium-kpi kpi-link" href={href}>
      <div className="kpi-label">{title}</div>
      <div className="kpi-value" style={{ fontSize: 22 }}>
        {value}
      </div>
      <div className="kpi-meta">{meta}</div>
      <div className="kpi-drill">Open underlying records →</div>
    </Link>
  );
}

function MetricRow({
  label,
  value,
  percent,
}: {
  label: string;
  value: string;
  percent?: number;
}) {
  return (
    <div style={{ marginTop: 10 }}>
      <div
        style={{ display: "flex", justifyContent: "space-between", gap: 12 }}
      >
        <span className="card-muted">{label}</span>
        <strong>{value}</strong>
      </div>
      {percent != null && (
        <div
          style={{
            marginTop: 5,
            height: 5,
            borderRadius: 999,
            background: "var(--aal-border)",
            overflow: "hidden",
          }}
        >
          <div
            style={{
              width: `${Math.max(0, Math.min(100, percent))}%`,
              height: "100%",
              background: "currentColor",
            }}
          />
        </div>
      )}
    </div>
  );
}

function Rule({
  title,
  detail,
  href,
}: {
  title: string;
  detail: string;
  href: string;
}) {
  return (
    <Link className="card" href={href} style={{ textDecoration: "none" }}>
      <div className="eyebrow">{title}</div>
      <div className="card-muted">{detail}</div>
    </Link>
  );
}
