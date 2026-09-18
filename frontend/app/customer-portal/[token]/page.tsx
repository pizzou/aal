"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import {
  ApiError,
  publicTrackingApi,
  PublicShipmentView,
} from "@/lib/api-client";

export default function CustomerPortalPage() {
  const params = useParams<{ token: string }>();
  const [data, setData] = useState<PublicShipmentView | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (params.token)
      publicTrackingApi
        .track(params.token)
        .then(setData)
        .catch((e) =>
          setError(
            e instanceof ApiError ? e.message : "Tracking link not found",
          ),
        );
  }, [params.token]);
  if (error)
    return (
      <main className="page">
        <div className="alert alert-error">{error}</div>
      </main>
    );
  if (!data)
    return (
      <main className="page">
        <div className="card">Loading secure shipment visibility…</div>
      </main>
    );
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL CUSTOMER PORTAL</div>
          <h1 className="page-title">{data.referenceCode}</h1>
          <p className="page-subtitle">
            {data.originAddress} → {data.destinationAddress}
          </p>
        </div>
        <span className="status status-success">{data.status}</span>
      </div>
      <div className="grid grid-4">
        <div className="card kpi">
          <div className="kpi-label">Transport</div>
          <div className="kpi-value" style={{ fontSize: 18 }}>
            {data.transportMode}
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Carrier</div>
          <div className="kpi-value" style={{ fontSize: 18 }}>
            {data.carrierName || "—"}
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Reference</div>
          <div className="kpi-value" style={{ fontSize: 18 }}>
            {data.carrierReferenceNumber || "—"}
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">ETA</div>
          <div className="kpi-value" style={{ fontSize: 18 }}>
            {data.eta ? new Date(data.eta).toLocaleString() : "—"}
          </div>
        </div>
      </div>
      <section className="card" style={{ marginTop: 14 }}>
        <h2 className="card-title">Shipment milestones</h2>
        {data.events.map((x, i) => (
          <div className="metric-row" key={`${x.occurredAt}-${i}`}>
            <span>
              <strong>{x.eventType}</strong>
              <small>{x.location || "Location not provided"}</small>
            </span>
            <strong>
              {new Date(x.occurredAt).toLocaleString()}
              <small>{x.notes || ""}</small>
            </strong>
          </div>
        ))}
        {!data.events.length && (
          <div className="empty">No milestones have been published yet.</div>
        )}
      </section>
      <section className="grid grid-2" style={{ marginTop: 14 }}>
        <div className="card">
          <h2 className="card-title">Authorized documents</h2>
          {data.documents.map((d, i) => (
            <div className="metric-row" key={`${d.documentType}-${i}`}>
              <span>
                {d.documentType}
                <small>{new Date(d.createdAt).toLocaleDateString()}</small>
              </span>
              <span className="status status-neutral">{d.status}</span>
            </div>
          ))}
          {!data.documents.length && (
            <div className="empty">
              No customer-visible documents have been published.
            </div>
          )}
        </div>
        <div className="card">
          <h2 className="card-title">Proof of delivery</h2>
          {data.pod ? (
            <div className="stack-list">
              <div className="metric-row">
                <span>Recipient</span>
                <strong>{data.pod.recipientName || "—"}</strong>
              </div>
              <div className="metric-row">
                <span>Delivered</span>
                <strong>
                  {new Date(data.pod.deliveredAt).toLocaleString()}
                </strong>
              </div>
              <div className="metric-row">
                <span>Evidence</span>
                <strong>
                  {data.pod.evidenceAvailable ? "Available" : "Not attached"}
                </strong>
              </div>
            </div>
          ) : (
            <div className="empty">
              POD will appear here after delivery confirmation.
            </div>
          )}
        </div>
      </section>
    </main>
  );
}
