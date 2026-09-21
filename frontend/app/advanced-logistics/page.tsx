"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { advancedLogisticsApi, AdvancedAnalytics } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

function money(value: unknown) {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 2 }) : "0";
}

export default function AdvancedLogisticsPage() {
  const { accessToken, isLoading } = useAuth();
  const [analytics, setAnalytics] = useState<AdvancedAnalytics | null>(null);
  const [capabilities, setCapabilities] = useState<Record<string, unknown> | null>(null);
  const [integrations, setIntegrations] = useState<Record<string, unknown>[]>([]);
  const [error, setError] = useState("");

  async function load() {
    try {
      const [a, c, i] = await Promise.all([
        advancedLogisticsApi.analytics(),
        advancedLogisticsApi.capabilities(),
        advancedLogisticsApi.integrations(),
      ]);
      setAnalytics(a);
      setCapabilities(c);
      setIntegrations(i);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unable to load advanced logistics control.");
    }
  }

  useEffect(() => {
    if (accessToken) load();
  }, [accessToken]);

  if (isLoading || !accessToken) return null;

  const shipmentStats = analytics?.shipments ?? {};
  const receivableStats = analytics?.receivables ?? {};

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / ENTERPRISE LOGISTICS OS</div>
          <h1 className="page-title">Advanced Logistics Control</h1>
          <p className="page-subtitle">
            One operating layer across commercial pricing, shipment execution,
            multimodal transport, warehouse, customs, finance, exceptions and integrations.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/shipments">Shipment Register</Link>
          <Link className="btn btn-primary" href="/commercial">Sales & Quotations</Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      <section className="grid grid-4">
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Total shipments</div>
          <div className="kpi-value">{money(shipmentStats.total)}</div>
          <div className="kpi-meta">{money(shipmentStats.active)} active / {money(shipmentStats.delivered)} delivered</div>
        </div>
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Revenue</div>
          <div className="kpi-value">{money(shipmentStats.revenue)}</div>
          <div className="kpi-meta">Canonical shipment revenue</div>
        </div>
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Gross profit</div>
          <div className="kpi-value">{money(shipmentStats.gross_profit)}</div>
          <div className="kpi-meta">Server-calculated operating margin base</div>
        </div>
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Outstanding</div>
          <div className="kpi-value">{money(receivableStats.outstanding)}</div>
          <div className="kpi-meta">{money(receivableStats.overdue)} overdue</div>
        </div>
      </section>

      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <section className="card">
          <div className="eyebrow">OPERATING COVERAGE</div>
          <h2 className="card-title">All enterprise phases</h2>
          <div className="grid grid-2" style={{ marginTop: 12 }}>
            {Array.isArray(capabilities?.phases) &&
              (capabilities.phases as string[]).map((phase) => (
                <div key={phase} className="card" style={{ padding: 12 }}>
                  <strong>{phase.replaceAll("_", " ")}</strong>
                  <div className="card-muted">Implemented operating layer</div>
                </div>
              ))}
          </div>
        </section>

        <section className="card">
          <div className="eyebrow">MULTIMODAL PERFORMANCE</div>
          <h2 className="card-title">Transport modes</h2>
          <div style={{ marginTop: 12 }}>
            {(analytics?.modes ?? []).map((row, index) => (
              <div key={String(row.mode ?? index)} style={{ display: "flex", justifyContent: "space-between", padding: "10px 0", borderBottom: "1px solid #eee" }}>
                <strong>{String(row.mode ?? "—")}</strong>
                <span>{money(row.count)} shipments</span>
              </div>
            ))}
          </div>
        </section>
      </div>

      <section className="card" style={{ marginTop: 18 }}>
        <div className="eyebrow">CARRIER PERFORMANCE</div>
        <h2 className="card-title">Operational scorecards</h2>
        {(analytics?.carriers ?? []).length === 0 ? (
          <p className="card-muted">No carrier performance events have been recorded yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Carrier</th><th>Events</th><th>Average score</th><th>On time</th></tr></thead>
              <tbody>
                {(analytics?.carriers ?? []).map((row, index) => (
                  <tr key={`${String(row.carrier_name)}-${index}`}>
                    <td>{String(row.carrier_name ?? "—")}</td>
                    <td>{money(row.events)}</td>
                    <td>{money(row.average_score)}</td>
                    <td>{money(row.on_time)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card" style={{ marginTop: 18 }}>
        <div className="eyebrow">INTEGRATION CONTROL</div>
        <h2 className="card-title">Provider readiness</h2>
        <p className="card-muted">
          AAL now has adapter-ready integration records. External airline, ocean,
          customs, payment, GPS and accounting providers still require credentials,
          sandbox/UAT and provider-specific mapping before being treated as live.
        </p>
        {(integrations ?? []).map((item, index) => (
          <div key={String(item.id ?? index)} style={{ display: "flex", justifyContent: "space-between", padding: "10px 0", borderBottom: "1px solid #eee" }}>
            <span>{String(item.display_name ?? item.code ?? "Integration")}</span>
            <span className="status status-neutral">{String(item.health_status ?? "NOT_CONFIGURED")}</span>
          </div>
        ))}
      </section>
    </main>
  );
}
