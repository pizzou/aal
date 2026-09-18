"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { logisticsApi } from "@/lib/api-client";

export default function OceanPage() {
  const [voyages, setVoyages] = useState<Record<string, unknown>[]>([]);
  const [bookings, setBookings] = useState<Record<string, unknown>[]>([]);
  useEffect(() => {
    Promise.all([logisticsApi.oceanVoyages(), logisticsApi.oceanBookings()])
      .then(([v, b]) => {
        setVoyages(v);
        setBookings(b);
      })
      .catch(() => {
        setVoyages([]);
        setBookings([]);
      });
  }, []);
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">OCEAN FREIGHT</div>
          <h1 className="page-title">Ocean Control Desk</h1>
          <p className="page-subtitle">
            Voyages, bookings, containers, VGM and B/L workflows attached to the
            multimodal shipment lifecycle.
          </p>
        </div>
        <div className="actions">
          <Link className="btn btn-primary" href="/shipments">
            Open shipment register
          </Link>
          <Link className="btn" href="/multimodal">
            Mode control
          </Link>
        </div>
      </div>
      <section className="grid grid-4">
        <div className="card kpi">
          <div className="kpi-label">Voyages</div>
          <div className="kpi-value">{voyages.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Open bookings</div>
          <div className="kpi-value">{bookings.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Container workflow</div>
          <div className="kpi-value">VGM</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Documents</div>
          <div className="kpi-value">MBL / HBL</div>
        </div>
      </section>
      <section className="card" style={{ marginTop: 16 }}>
        <h2 className="card-title">Voyage schedule</h2>
        {voyages.length === 0 ? (
          <div className="empty">No ocean voyages loaded yet.</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Voyage</th>
                  <th>Carrier</th>
                  <th>Vessel</th>
                  <th>Lane</th>
                  <th>ETD</th>
                  <th>ETA</th>
                </tr>
              </thead>
              <tbody>
                {voyages.map((x) => (
                  <tr key={String(x.id)}>
                    <td>{String(x.voyageNumber || "—")}</td>
                    <td>{String(x.carrierName || "—")}</td>
                    <td>{String(x.vesselName || "—")}</td>
                    <td>
                      {String(x.originPort || "—")} →{" "}
                      {String(x.destinationPort || "—")}
                    </td>
                    <td>
                      {x.etd
                        ? new Date(String(x.etd)).toLocaleDateString()
                        : "—"}
                    </td>
                    <td>
                      {x.eta
                        ? new Date(String(x.eta)).toLocaleDateString()
                        : "—"}
                    </td>
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
