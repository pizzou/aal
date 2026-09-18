"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { logisticsApi } from "@/lib/api-client";

export default function RailPage() {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  useEffect(() => {
    logisticsApi
      .railConsignments()
      .then(setRows)
      .catch(() => setRows([]));
  }, []);
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">RAIL FREIGHT</div>
          <h1 className="page-title">Rail Operations</h1>
          <p className="page-subtitle">
            Rail consignments remain attached to the same multimodal shipment,
            terminal and delivery timeline.
          </p>
        </div>
        <div className="actions">
          <Link className="btn btn-primary" href="/multimodal">
            Mode control
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
          <div className="kpi-label">Terminal flow</div>
          <div className="kpi-value">Linked</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Train / wagon</div>
          <div className="kpi-value">Tracked</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Intermodal</div>
          <div className="kpi-value">Ready</div>
        </div>
      </section>
      <section className="card" style={{ marginTop: 16 }}>
        <h2 className="card-title">Rail queue</h2>
        {rows.length === 0 ? (
          <div className="empty">
            No rail consignments have been recorded yet.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Consignment</th>
                  <th>Train</th>
                  <th>Wagons</th>
                  <th>Origin</th>
                  <th>Destination</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((x) => (
                  <tr key={String(x.id)}>
                    <td>{String(x.railConsignmentNumber || "—")}</td>
                    <td>{String(x.trainNumber || "—")}</td>
                    <td>{String(x.wagonNumbers || "—")}</td>
                    <td>{String(x.originTerminal || "—")}</td>
                    <td>{String(x.destinationTerminal || "—")}</td>
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
