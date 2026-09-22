"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { advancedLogisticsApi } from "@/lib/api-client";
import {
  pendingMobileOperations,
  queueMobileOperation,
  removeMobileOperation,
  type MobileOperation,
} from "@/lib/offline-queue";
import { useAuth } from "@/lib/auth-context";

function deviceIdentity() {
  if (typeof window === "undefined") return "AAL-WEB";
  const stored = localStorage.getItem("aal-device-id");
  if (stored) return stored;
  const generated = `WEB-${crypto.randomUUID()}`;
  localStorage.setItem("aal-device-id", generated);
  return generated;
}

async function compressImage(file: File) {
  const source = await createImageBitmap(file);
  const scale = Math.min(1, 1280 / Math.max(source.width, source.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(source.width * scale));
  canvas.height = Math.max(1, Math.round(source.height * scale));
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Unable to process photo");
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", 0.72);
}

export default function MobileOperationsPage() {
  const { accessToken, isLoading } = useAuth();
  const [shipmentId, setShipmentId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [recipient, setRecipient] = useState("");
  const [photoData, setPhotoData] = useState("");
  const [status, setStatus] = useState("");
  const [online, setOnline] = useState(true);
  const [pending, setPending] = useState(0);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const drawing = useRef(false);

  const refreshPending = useCallback(
    async () => setPending((await pendingMobileOperations()).length),
    [],
  );

  const flush = useCallback(async () => {
    if (!navigator.onLine || !accessToken) return;
    const items = await pendingMobileOperations();
    for (const item of items) {
      try {
        await advancedLogisticsApi.mobileSync(item);
        await removeMobileOperation(item.operationId);
      } catch {
        break;
      }
    }
    await refreshPending();
  }, [accessToken, refreshPending]);

  useEffect(() => {
    setDeviceId(deviceIdentity());
    setOnline(navigator.onLine);
    refreshPending();
    const onOnline = () => {
      setOnline(true);
      void flush();
    };
    const onOffline = () => setOnline(false);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    return () => {
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    };
  }, [flush, refreshPending]);

  useEffect(() => {
    if (accessToken) void flush();
  }, [accessToken, flush]);

  if (isLoading || !accessToken) return null;

  function clearSignature() {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    ctx?.clearRect(0, 0, canvas.width, canvas.height);
  }

  function pointerDown(event: React.PointerEvent<HTMLCanvasElement>) {
    drawing.current = true;
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.setPointerCapture(event.pointerId);
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const rect = canvas.getBoundingClientRect();
    ctx.beginPath();
    ctx.moveTo(event.clientX - rect.left, event.clientY - rect.top);
  }

  function pointerMove(event: React.PointerEvent<HTMLCanvasElement>) {
    if (!drawing.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;
    const rect = canvas.getBoundingClientRect();
    ctx.lineWidth = 2;
    ctx.lineCap = "round";
    ctx.lineTo(event.clientX - rect.left, event.clientY - rect.top);
    ctx.stroke();
  }

  function pointerUp() {
    drawing.current = false;
  }

  async function syncPOD() {
    setStatus("");
    try {
      if (!shipmentId.trim() || !recipient.trim())
        throw new Error("Shipment ID and recipient are required.");
      const signatureUri = canvasRef.current?.toDataURL("image/png") || "";
      const operation: MobileOperation = {
        deviceId,
        operationId: crypto.randomUUID(),
        entityType: "POD",
        entityId: shipmentId.trim(),
        payloadJson: JSON.stringify({
          shipmentId: shipmentId.trim(),
          recipientName: recipient.trim(),
          photoDataUrl: photoData || undefined,
          signatureDataUrl: signatureUri,
          capturedAt: new Date().toISOString(),
          deviceId,
          offlineCaptured: !navigator.onLine,
        }),
      };

      if (!navigator.onLine) {
        await queueMobileOperation(operation);
        await refreshPending();
        setStatus("Offline: POD safely queued on this device.");
        return;
      }

      try {
        await advancedLogisticsApi.mobileSync(operation);
        setStatus("POD synchronized successfully.");
      } catch {
        await queueMobileOperation(operation);
        setStatus(
          "Network unavailable: POD queued for automatic synchronization.",
        );
      }
      await refreshPending();
      clearSignature();
      setPhotoData("");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Unable to capture POD.");
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / MOBILE OPERATIONS</div>
          <h1 className="page-title">Driver & Warehouse Mobile Workbench</h1>
          <p className="page-subtitle">
            Offline-capable POD capture, device identity, evidence collection
            and automatic synchronization.
          </p>
        </div>
        <div className="badge">
          {online ? "ONLINE" : "OFFLINE"} · {pending} pending
        </div>
      </div>

      <section className="card" style={{ maxWidth: 760 }}>
        <div className="eyebrow">PROOF OF DELIVERY</div>
        <h2 className="card-title">Capture delivery evidence</h2>
        <div style={{ display: "grid", gap: 12, marginTop: 16 }}>
          <input
            value={shipmentId}
            onChange={(e) => setShipmentId(e.target.value)}
            placeholder="Shipment UUID"
          />
          <input
            value={recipient}
            onChange={(e) => setRecipient(e.target.value)}
            placeholder="Recipient name"
          />
          <label className="card-muted">
            Delivery photo
            <input
              type="file"
              accept="image/*"
              capture="environment"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (file) setPhotoData(await compressImage(file));
              }}
            />
          </label>
          <canvas
            ref={canvasRef}
            width={680}
            height={180}
            style={{
              width: "100%",
              maxWidth: 680,
              border: "1px solid var(--border, #d0d5dd)",
              borderRadius: 10,
              touchAction: "none",
              background: "#fff",
            }}
            onPointerDown={pointerDown}
            onPointerMove={pointerMove}
            onPointerUp={pointerUp}
            onPointerCancel={pointerUp}
          />
          <button className="btn" onClick={clearSignature}>
            Clear signature
          </button>
          <button className="btn btn-primary" onClick={syncPOD}>
            {online ? "Save & synchronize POD" : "Save POD offline"}
          </button>
          {photoData && (
            <img
              src={photoData}
              alt="Delivery evidence preview"
              style={{ width: "100%", maxWidth: 320, borderRadius: 10 }}
            />
          )}
          {status && <div className="alert">{status}</div>}
        </div>
      </section>
    </main>
  );
}
