"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  airCargoApi,
  ApiError,
  Shipment,
  shipmentsApi,
  operationsApi,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

export default function DocumentsPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [selected, setSelected] = useState("");
  const [docs, setDocs] = useState<Record<string, unknown>[]>([]);
  const [pod, setPod] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);
  useEffect(() => {
    if (accessToken)
      shipmentsApi
        .list()
        .then((x) => setShipments(x.content))
        .catch((e) =>
          setError(
            e instanceof ApiError ? e.message : "Unable to load shipments",
          ),
        );
  }, [accessToken]);
  async function open(id: string) {
    setSelected(id);
    setError("");
    try {
      const [d, p] = await Promise.all([
        airCargoApi.shipmentDocuments(id),
        operationsApi.pod(id).catch(() => null),
      ]);
      setDocs(d);
      setPod(p as Record<string, unknown> | null);
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to load document room",
      );
    }
  }
  if (isLoading || !accessToken) return null;
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">DOCUMENT CONTROL</div>
          <h1 className="page-title">Shipment Documents & Customs</h1>
          <p className="page-subtitle">
            One document room for AWB, customs declarations, cargo documents and
            proof of delivery, linked to the canonical shipment.
          </p>
        </div>
        <Link className="btn" href="/air-cargo">
          Air cargo documents
        </Link>
      </div>
      {error && <div className="alert alert-error">{error}</div>}
      <section className="card">
        <div className="page-head">
          <div>
            <h2 className="card-title">Shipment document room</h2>
            <div className="card-muted">
              Select a shipment. Only its tenant-scoped records are returned.
            </div>
          </div>
          <select
            className="search"
            value={selected}
            onChange={(e) => open(e.target.value)}
          >
            <option value="">Select shipment…</option>
            {shipments.map((s) => (
              <option key={s.id} value={s.id}>
                {s.referenceCode} · {s.clientName || "No customer"}
              </option>
            ))}
          </select>
        </div>
        {!selected ? (
          <div className="empty">
            Select a shipment to inspect its documents, customs state and POD.
          </div>
        ) : (
          <>
            <div className="grid grid-4" style={{ marginBottom: 14 }}>
              <div className="card kpi">
                <div className="kpi-label">Documents</div>
                <div className="kpi-value">{docs.length}</div>
              </div>
              <div className="card kpi">
                <div className="kpi-label">AWB</div>
                <div className="kpi-value">
                  {docs.filter((x) => x.category === "AWB").length}
                </div>
              </div>
              <div className="card kpi">
                <div className="kpi-label">Customs</div>
                <div className="kpi-value">
                  {docs.filter((x) => x.category === "CUSTOMS").length}
                </div>
              </div>
              <div className="card kpi">
                <div className="kpi-label">POD</div>
                <div className="kpi-value">{pod ? "Captured" : "Pending"}</div>
              </div>
            </div>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Category</th>
                    <th>Type / number</th>
                    <th>Status</th>
                    <th>Reference</th>
                    <th>File</th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((d, i) => (
                    <tr key={String(d.id || i)}>
                      <td>{String(d.category || "DOCUMENT")}</td>
                      <td>{String(d.documentType || d.number || "—")}</td>
                      <td>{String(d.status || "—")}</td>
                      <td>{String(d.number || "—")}</td>
                      <td>
                        {d.fileUri ? <span>{String(d.fileUri)}</span> : "—"}
                      </td>
                    </tr>
                  ))}
                  {!docs.length && (
                    <tr>
                      <td colSpan={5} className="empty">
                        No document records for this shipment.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className="card" style={{ marginTop: 14 }}>
              <h2 className="card-title">Proof of delivery</h2>
              {pod ? (
                <div className="stack-list">
                  <div className="metric-row">
                    <span>Recipient</span>
                    <strong>{String(pod.recipientName || "—")}</strong>
                  </div>
                  <div className="metric-row">
                    <span>Delivered at</span>
                    <strong>
                      {pod.deliveredAt
                        ? new Date(String(pod.deliveredAt)).toLocaleString()
                        : "—"}
                    </strong>
                  </div>
                  <div className="metric-row">
                    <span>Evidence</span>
                    <strong>
                      {pod.signatureUri || pod.photoUri
                        ? "Attached"
                        : "Not attached"}
                    </strong>
                  </div>
                </div>
              ) : (
                <div className="empty">
                  No POD captured. Dispatch can record POD from the trip
                  execution workflow.
                </div>
              )}
            </div>
          </>
        )}
      </section>
    </main>
  );
}
