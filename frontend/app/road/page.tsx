"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { logisticsApi } from "@/lib/api-client";

export default function RoadPage() {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  useEffect(() => {
    logisticsApi
      .roadConsignments()
      .then(setRows)
      .catch(() => setRows([]));
  }, []);
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">ROAD EXECUTION</div>
          <h1 className="page-title">Road Operations</h1>
          <p className="page-subtitle">
            Road consignments remain linked to the canonical shipment, dispatch,
            vehicle, driver and proof-of-delivery workflow.
          </p>
        </div>
        <div className="actions">
          <Link className="btn btn-primary" href="/trips">
            Open dispatch
          </Link>
          <Link className="btn" href="/shipments">
            Shipment register
          </Link>
        </div>
      </div>
      <section className="grid grid-4">
        <div className="card kpi">
          <div className="kpi-label">Consignments</div>
          <div className="kpi-value">{rows.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">CMR</div>
          <div className="kpi-value">Live</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Delivery</div>
          <div className="kpi-value">POD</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Execution</div>
          <div className="kpi-value">Linked</div>
        </div>
      </section>
      <section className="card" style={{ marginTop: 16 }}>
        <h2 className="card-title">Dispatch queue</h2>
        {rows.length === 0 ? (
          <div className="empty">
            No road consignments have been recorded yet.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>CMR</th>
                  <th>Vehicle</th>
                  <th>Driver</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((x) => (
                  <tr key={String(x.id)}>
                    <td>{String(x.cmrNumber || "—")}</td>
                    <td>{String(x.vehicleRegistration || "—")}</td>
                    <td>{String(x.driverName || "—")}</td>
                    <td>{String(x.status || "PLANNED")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
