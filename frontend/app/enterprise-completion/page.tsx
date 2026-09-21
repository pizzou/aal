"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { enterpriseCompletionApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type EnterpriseItem = {
  id: string;
  name: string;
  status: string;
};

type EnterprisePhase = {
  phase: string;
  name: string;
  status: string;
  items: EnterpriseItem[];
};

type Forecast = {
  nextMonthRevenue?: number;
  nextMonthGrossProfit?: number;
};

export default function EnterpriseCompletionPage() {
  const { accessToken, isLoading } = useAuth();
  const [phases, setPhases] = useState<EnterprisePhase[]>([]);
  const [forecast, setForecast] = useState<Forecast | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setError("");
      const [items, fc] = await Promise.all([
        enterpriseCompletionApi.checklist(),
        enterpriseCompletionApi.forecast(),
      ]);
      setPhases(items as EnterprisePhase[]);
      setForecast(fc as Forecast);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Unable to load enterprise completion status.",
      );
    }
  }, []);

  useEffect(() => {
    if (accessToken) void load();
  }, [accessToken, load]);

  if (isLoading || !accessToken) return null;

  const blocked = phases.reduce(
    (count, phase) =>
      count + (phase.status === "REQUIRES_EXTERNAL_VERIFICATION" ? 1 : 0),
    0,
  );

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / ENTERPRISE READINESS</div>
          <h1 className="page-title">Enterprise Readiness</h1>
          <p className="page-subtitle">
            One canonical checklist across the AAL logistics operating platform.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/advanced-logistics">
            Advanced Control
          </Link>
          <Link className="btn btn-primary" href="/mobile-ops">
            Mobile Operations
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      <section className="grid grid-3">
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Phases</div>
          <div className="kpi-value">{phases.length}</div>
          <div className="kpi-meta">0–18 canonical roadmap</div>
        </div>
        <div className="card kpi premium-kpi">
          <div className="kpi-label">External verification</div>
          <div className="kpi-value">{blocked}</div>
          <div className="kpi-meta">Provider / operational gates remaining</div>
        </div>
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Revenue forecast</div>
          <div className="kpi-value">
            {Number(forecast?.nextMonthRevenue ?? 0).toLocaleString(undefined, {
              maximumFractionDigits: 2,
            })}
          </div>
          <div className="kpi-meta">Trailing six-month average</div>
        </div>
      </section>

      <section className="card" style={{ marginTop: 18 }}>
        <div className="eyebrow">MASTER CHECKLIST</div>
        <h2 className="card-title">Implementation coverage</h2>
        <div className="table-wrap" style={{ marginTop: 12 }}>
          <table className="table">
            <thead>
              <tr>
                <th>Phase</th>
                <th>Domain</th>
                <th>Status</th>
                <th>Items</th>
              </tr>
            </thead>
            <tbody>
              {phases.map((phase) => (
                <tr key={phase.phase}>
                  <td>{phase.phase}</td>
                  <td>
                    <strong>{phase.name}</strong>
                  </td>
                  <td>
                    <span className="status status-neutral">
                      {phase.status}
                    </span>
                  </td>
                  <td>{phase.items.length}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
