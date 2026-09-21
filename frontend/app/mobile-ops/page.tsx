"use client";

import { useEffect, useState } from "react";
import { advancedLogisticsApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

export default function MobileOperationsPage() {
  const { accessToken, isLoading } = useAuth();
  const [shipmentId, setShipmentId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [recipient, setRecipient] = useState("");
  const [photoUri, setPhotoUri] = useState("");
  const [signatureUri, setSignatureUri] = useState("");
  const [status, setStatus] = useState("");

  useEffect(() => {
    if (typeof window !== "undefined") {
      setDeviceId(localStorage.getItem("aal-device-id") || `WEB-${crypto.randomUUID()}`);
    }
  }, []);

  if (isLoading || !accessToken) return null;

  async function syncPOD() {
    setStatus("");
    try {
      if (!shipmentId.trim() || !recipient.trim()) throw new Error("Shipment ID and recipient are required.");
      await advancedLogisticsApi.mobileSync({
        deviceId,
        operationId: crypto.randomUUID(),
        entityType: "POD",
        entityId: shipmentId.trim(),
        payloadJson: JSON.stringify({
          shipmentId: shipmentId.trim(),
          recipientName: recipient.trim(),
          photoUri: photoUri.trim() || undefined,
          signatureUri: signatureUri.trim() || undefined,
          capturedAt: new Date().toISOString(),
        }),
      });
      setStatus("POD capture queued for synchronization.");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Unable to queue mobile operation.");
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / MOBILE OPERATIONS</div>
          <h1 className="page-title">Driver & Warehouse Mobile Workbench</h1>
          <p className="page-subtitle">
            Responsive field workflow with an idempotent synchronization queue.
            It can be installed as a browser PWA when the deployment adds the manifest/service worker.
          </p>
        </div>
      </div>

      <section className="card" style={{ maxWidth: 680 }}>
        <div className="eyebrow">PROOF OF DELIVERY</div>
        <h2 className="card-title">Capture delivery evidence</h2>
        <div style={{ display: "grid", gap: 12, marginTop: 16 }}>
          <input value={shipmentId} onChange={e => setShipmentId(e.target.value)} placeholder="Shipment UUID" />
          <input value={recipient} onChange={e => setRecipient(e.target.value)} placeholder="Recipient name" />
          <input value={photoUri} onChange={e => setPhotoUri(e.target.value)} placeholder="Photo URI / uploaded evidence reference" />
          <input value={signatureUri} onChange={e => setSignatureUri(e.target.value)} placeholder="Signature URI / uploaded evidence reference" />
          <button className="btn btn-primary" onClick={syncPOD}>Queue POD sync</button>
          {status && <div className="alert">{status}</div>}
        </div>
      </section>
    </main>
  );
}
