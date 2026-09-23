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
  const [barcode, setBarcode] = useState("");
  const [barcodeType, setBarcodeType] = useState("CODE128");
  const [location, setLocation] = useState<{
    latitude: number;
    longitude: number;
    accuracy?: number;
  } | null>(null);
  const [scanStatus, setScanStatus] = useState("");
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const cameraStream = useRef<MediaStream | null>(null);
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

  function stopBarcodeScanner() {
    cameraStream.current?.getTracks().forEach((track) => track.stop());
    cameraStream.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }

  async function startBarcodeScanner() {
    setScanStatus("");
    const BarcodeDetectorCtor = (
      window as typeof window & {
        BarcodeDetector?: new (options?: { formats?: string[] }) => {
          detect(
            video: HTMLVideoElement,
          ): Promise<Array<{ rawValue?: string }>>;
        };
      }
    ).BarcodeDetector;

    if (!BarcodeDetectorCtor) {
      setScanStatus(
        "This device/browser does not expose native barcode scanning. Enter the barcode manually.",
      );
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false,
      });
      cameraStream.current = stream;
      const video = videoRef.current;
      if (!video) {
        stopBarcodeScanner();
        return;
      }
      video.srcObject = stream;
      await video.play();
      const detector = new BarcodeDetectorCtor({
        formats: ["qr_code", "code_128", "ean_13", "ean_8", "upc_a", "upc_e"],
      });
      setScanStatus("Camera scanner active. Point it at the barcode.");
      const scan = async () => {
        if (!cameraStream.current || !videoRef.current) return;
        try {
          const hits = await detector.detect(videoRef.current);
          const raw = hits[0]?.rawValue?.trim();
          if (raw) {
            setBarcode(raw);
            setScanStatus(`Barcode captured: ${raw}`);
            stopBarcodeScanner();
            return;
          }
        } catch {
          // Continue scanning until the camera is stopped.
        }
        window.setTimeout(() => void scan(), 300);
      };
      void scan();
    } catch (e) {
      setScanStatus(
        e instanceof Error ? e.message : "Unable to access the camera scanner.",
      );
      stopBarcodeScanner();
    }
  }

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

  async function captureLocation() {
    setScanStatus("");
    if (!navigator.geolocation) {
      setScanStatus("Location is not supported by this device.");
      return;
    }
    await new Promise<void>((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          setLocation({
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: position.coords.accuracy,
          });
          setScanStatus("Current GPS location captured.");
          resolve();
        },
        (error) => {
          setScanStatus(error.message || "Unable to capture GPS location.");
          resolve();
        },
        { enableHighAccuracy: true, maximumAge: 15000, timeout: 10000 },
      );
    });
  }

  async function registerBarcode() {
    const value = barcode.trim();
    if (!value) {
      setScanStatus("Enter or scan a barcode first.");
      return;
    }
    try {
      await advancedLogisticsApi.warehouseBarcode({
        shipmentId: shipmentId.trim() || undefined,
        barcode: value,
        barcodeType,
      });
      setScanStatus("Barcode registered in the warehouse traceability ledger.");
    } catch (e) {
      setScanStatus(
        e instanceof Error ? e.message : "Unable to register barcode.",
      );
    }
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
          barcode: barcode.trim() || undefined,
          barcodeType: barcodeType || undefined,
          location: location || undefined,
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
        <div className="eyebrow" style={{ marginTop: 24 }}>
          FIELD TRACEABILITY
        </div>
        <h2 className="card-title">Barcode & GPS evidence</h2>
        <div style={{ display: "grid", gap: 12, marginTop: 16 }}>
          <input
            value={barcode}
            onChange={(e) => setBarcode(e.target.value)}
            inputMode="numeric"
            placeholder="Scan or enter barcode / QR value"
          />
          <select
            value={barcodeType}
            onChange={(e) => setBarcodeType(e.target.value)}
          >
            <option value="CODE128">CODE128</option>
            <option value="QR">QR</option>
            <option value="EAN13">EAN13</option>
            <option value="GS1-128">GS1-128</option>
          </select>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn" onClick={registerBarcode}>
              Register barcode
            </button>
            <button className="btn" onClick={startBarcodeScanner}>
              Scan with camera
            </button>
            <button className="btn" onClick={captureLocation}>
              Capture GPS
            </button>
            <button className="btn" onClick={stopBarcodeScanner}>
              Stop scanner
            </button>
          </div>
          <video
            ref={videoRef}
            muted
            playsInline
            style={{
              width: "100%",
              maxWidth: 420,
              borderRadius: 10,
              background: "#111",
            }}
          />
          {location && (
            <div className="card-muted">
              GPS: {location.latitude.toFixed(6)},{" "}
              {location.longitude.toFixed(6)}
              {location.accuracy ? ` · ±${Math.round(location.accuracy)}m` : ""}
            </div>
          )}
          {scanStatus && <div className="alert">{scanStatus}</div>}
        </div>

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
