"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import {
  shipmentsApi,
  sensorsApi,
  awbDownloadUrl,
  flightStatusApi,
  Shipment,
  TrackingEvent,
  SensorReading,
  NotificationRecord,
  FlightStatusResult,
  ApiError,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

const EVENT_TYPES = [
  "BOOKED",
  "PICKED_UP",
  "DEPARTED_ORIGIN",
  "IN_TRANSIT",
  "CUSTOMS_HOLD",
  "ARRIVED_DESTINATION",
  "OUT_FOR_DELIVERY",
  "DELIVERED",
  "EXCEPTION",
];

export default function ShipmentTrackingPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const { accessToken, isLoading } = useAuth();
  const [events, setEvents] = useState<TrackingEvent[]>([]);
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [copied, setCopied] = useState(false);
  const [readings, setReadings] = useState<SensorReading[]>([]);
  const [readingForm, setReadingForm] = useState({
    temperatureCelsius: "",
    humidityPercent: "",
  });
  const [thresholdForm, setThresholdForm] = useState({
    minTemperatureCelsius: "",
    maxTemperatureCelsius: "",
  });
  const [weightKg, setWeightKg] = useState("");
  const [notificationEmail, setNotificationEmail] = useState("");
  const [notifications, setNotifications] = useState<NotificationRecord[]>([]);
  const [flightNumber, setFlightNumber] = useState("");
  const [flightStatus, setFlightStatus] = useState<FlightStatusResult | null>(
    null,
  );
  const [flightStatusError, setFlightStatusError] = useState<string | null>(
    null,
  );
  const [form, setForm] = useState({
    eventType: "IN_TRANSIT",
    location: "",
    notes: "",
  });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (accessToken) refresh();
  }, [accessToken]);

  async function refresh() {
    try {
      setEvents(await shipmentsApi.trackingHistory(params.id));
      const s = await shipmentsApi.get(params.id);
      setShipment(s);
      setWeightKg(s.weightKg != null ? String(s.weightKg) : "");
      setNotificationEmail(s.notificationEmail ?? "");
      setFlightNumber(s.flightNumber ?? "");
      setReadings((await sensorsApi.history(params.id)).content);
      setNotifications(
        (await shipmentsApi.notificationHistory(params.id)).content,
      );
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : "Failed to load tracking history",
      );
    }
  }

  async function saveWeight(e: React.FormEvent) {
    e.preventDefault();
    try {
      await shipmentsApi.updateWeight(params.id, Number(weightKg));
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save weight");
    }
  }

  async function saveNotificationEmail(e: React.FormEvent) {
    e.preventDefault();
    try {
      await shipmentsApi.updateNotificationEmail(params.id, notificationEmail);
      refresh();
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : "Failed to save notification email",
      );
    }
  }

  async function saveFlightNumber(e: React.FormEvent) {
    e.preventDefault();
    try {
      await shipmentsApi.updateFlightNumber(params.id, flightNumber);
      refresh();
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Failed to save flight number",
      );
    }
  }

  async function checkFlightStatus() {
    setFlightStatusError(null);
    try {
      setFlightStatus(await flightStatusApi.check(params.id));
      refresh();
    } catch (err) {
      setFlightStatusError(
        err instanceof ApiError ? err.message : "Flight status check failed",
      );
    }
  }

  async function submitReading(e: React.FormEvent) {
    e.preventDefault();
    try {
      await sensorsApi.recordReading(params.id, {
        temperatureCelsius: readingForm.temperatureCelsius
          ? Number(readingForm.temperatureCelsius)
          : undefined,
        humidityPercent: readingForm.humidityPercent
          ? Number(readingForm.humidityPercent)
          : undefined,
      });
      setReadingForm({ temperatureCelsius: "", humidityPercent: "" });
      refresh();
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Failed to record reading",
      );
    }
  }

  async function saveThreshold(e: React.FormEvent) {
    e.preventDefault();
    try {
      await sensorsApi.setThreshold(params.id, {
        minTemperatureCelsius: thresholdForm.minTemperatureCelsius
          ? Number(thresholdForm.minTemperatureCelsius)
          : undefined,
        maxTemperatureCelsius: thresholdForm.maxTemperatureCelsius
          ? Number(thresholdForm.maxTemperatureCelsius)
          : undefined,
      });
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Failed to save threshold",
      );
    }
  }

  function copyShareLink() {
    if (!shipment) return;
    const url = `${window.location.origin}/track/${shipment.trackingToken}`;
    navigator.clipboard.writeText(url);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      await shipmentsApi.addTrackingEvent(params.id, form);
      setForm({ ...form, location: "", notes: "" });
      refresh();
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Failed to add tracking event",
      );
    }
  }

  if (isLoading || !accessToken) return null;

  return (
    <main
      style={{ maxWidth: 700, margin: "40px auto", fontFamily: "sans-serif" }}
    >
      <Link href="/shipments">&larr; Shipments</Link>
      <h1>Tracking</h1>

      {shipment && (
        <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
          <button onClick={copyShareLink}>
            {copied ? "Copied!" : "Copy customer share link"}
          </button>
          <a
            href={awbDownloadUrl(shipment.id)}
            target="_blank"
            rel="noopener noreferrer"
          >
            <button type="button">Download AWB (PDF)</button>
          </a>
        </div>
      )}

      {shipment && (
        <>
          <section className="card" style={{ marginTop: 14 }}>
            <div className="eyebrow">SHIPMENT 360</div>
            <h2 className="card-title">{shipment.referenceCode}</h2>
            <div className="card-muted" style={{ marginBottom: 12 }}>
              Customer → Quote → Booking → Cargo → AWB → Flight / Transport →
              Documents → Customs → Delivery → POD → Invoice → Payment →
              Profitability
            </div>
            <div className="grid grid-4">
              <div className="kpi">
                <div className="kpi-label">Client revenue</div>
                <div className="kpi-value" style={{ fontSize: 18 }}>
                  {shipment.amountBilledToClient?.toLocaleString() || "—"}{" "}
                  {shipment.currency || ""}
                </div>
              </div>
              <div className="kpi">
                <div className="kpi-label">Total cost</div>
                <div className="kpi-value" style={{ fontSize: 18 }}>
                  {shipment.totalCost?.toLocaleString() || "—"}{" "}
                  {shipment.currency || ""}
                </div>
              </div>
              <div className="kpi">
                <div className="kpi-label">Gross profit</div>
                <div className="kpi-value" style={{ fontSize: 18 }}>
                  {shipment.grossProfit?.toLocaleString() || "—"}{" "}
                  {shipment.currency || ""}
                </div>
              </div>
              <div className="kpi">
                <div className="kpi-label">Receivable</div>
                <div className="kpi-value" style={{ fontSize: 18 }}>
                  {shipment.amountRemaining?.toLocaleString() || "—"}{" "}
                  {shipment.currency || ""}
                </div>
              </div>
            </div>
            <div className="actions" style={{ marginTop: 12 }}>
              <Link className="btn" href="/documents">
                Documents & customs
              </Link>
              <Link className="btn" href="/billing">
                Invoice & payment
              </Link>
              <Link className="btn" href="/trips">
                Dispatch / POD
              </Link>
            </div>
          </section>
        </>
      )}

      <form
        onSubmit={saveWeight}
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          margin: "12px 0",
        }}
      >
        <label style={{ fontSize: 14, color: "#555" }}>Weight (kg):</label>
        <input
          type="number"
          min={0}
          value={weightKg}
          onChange={(e) => setWeightKg(e.target.value)}
          style={{ width: 100 }}
        />
        <button type="submit">Save</button>
      </form>

      <form
        onSubmit={saveNotificationEmail}
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          margin: "12px 0",
        }}
      >
        <label style={{ fontSize: 14, color: "#555" }}>
          Notify customer at:
        </label>
        <input
          type="email"
          placeholder="customer@example.com"
          value={notificationEmail}
          onChange={(e) => setNotificationEmail(e.target.value)}
          style={{ width: 220 }}
        />
        <button type="submit">Save</button>
      </form>

      <form
        onSubmit={saveFlightNumber}
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          margin: "12px 0",
        }}
      >
        <label style={{ fontSize: 14, color: "#555" }}>Flight number:</label>
        <input
          placeholder="KQ100"
          value={flightNumber}
          onChange={(e) => setFlightNumber(e.target.value)}
          style={{ width: 120 }}
        />
        <button type="submit">Save</button>
        <button type="button" onClick={checkFlightStatus}>
          Check status
        </button>
      </form>
      {flightStatusError && (
        <p
          style={{
            color: flightStatusError.includes("not available")
              ? "#888"
              : "crimson",
            fontSize: 14,
          }}
        >
          {flightStatusError}
        </p>
      )}
      {flightStatus && (
        <p
          style={{
            fontSize: 14,
            color: flightStatus.significantDelay ? "crimson" : "#137333",
          }}
        >
          Status: {flightStatus.flightStatus} — departure +
          {flightStatus.departureDelayMinutes}min, arrival +
          {flightStatus.arrivalDelayMinutes}min
          {flightStatus.significantDelay && " — logged as an exception below"}
        </p>
      )}

      <form
        onSubmit={handleSubmit}
        style={{ display: "flex", gap: 8, margin: "20px 0" }}
      >
        <select
          value={form.eventType}
          onChange={(e) => setForm({ ...form, eventType: e.target.value })}
        >
          {EVENT_TYPES.map((t) => (
            <option key={t} value={t}>
              {t.replace(/_/g, " ")}
            </option>
          ))}
        </select>
        <input
          placeholder="Location (optional)"
          value={form.location}
          onChange={(e) => setForm({ ...form, location: e.target.value })}
        />
        <input
          placeholder="Notes (optional)"
          value={form.notes}
          onChange={(e) => setForm({ ...form, notes: e.target.value })}
        />
        <button type="submit">Add event</button>
      </form>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      <ol
        style={{
          listStyle: "none",
          padding: 0,
          borderLeft: "2px solid #ddd",
          marginLeft: 8,
        }}
      >
        {events.map((ev) => (
          <li
            key={ev.id}
            style={{ padding: "0 0 16px 20px", position: "relative" }}
          >
            <span
              style={{
                position: "absolute",
                left: -7,
                top: 4,
                width: 12,
                height: 12,
                borderRadius: "50%",
                background:
                  ev.eventType === "EXCEPTION" ? "crimson" : "#0070f3",
              }}
            />
            <strong>{ev.eventType.replace(/_/g, " ")}</strong>
            <div style={{ color: "#666", fontSize: 14 }}>
              {new Date(ev.occurredAt).toLocaleString()}
              {ev.location ? ` · ${ev.location}` : ""}
            </div>
            {ev.notes && <div style={{ fontSize: 14 }}>{ev.notes}</div>}
          </li>
        ))}
        {events.length === 0 && (
          <p style={{ color: "#888" }}>No tracking events yet.</p>
        )}
      </ol>

      <h2 style={{ marginTop: 24 }}>Cold-chain monitoring</h2>
      <details style={{ marginBottom: 12 }}>
        <summary style={{ cursor: "pointer", color: "#666", fontSize: 14 }}>
          Set threshold
        </summary>
        <form
          onSubmit={saveThreshold}
          style={{ display: "flex", gap: 8, marginTop: 8 }}
        >
          <input
            placeholder="Min °C"
            value={thresholdForm.minTemperatureCelsius}
            onChange={(e) =>
              setThresholdForm({
                ...thresholdForm,
                minTemperatureCelsius: e.target.value,
              })
            }
          />
          <input
            placeholder="Max °C"
            value={thresholdForm.maxTemperatureCelsius}
            onChange={(e) =>
              setThresholdForm({
                ...thresholdForm,
                maxTemperatureCelsius: e.target.value,
              })
            }
          />
          <button type="submit">Save threshold</button>
        </form>
      </details>
      <form
        onSubmit={submitReading}
        style={{ display: "flex", gap: 8, marginBottom: 12 }}
      >
        <input
          placeholder="Temp °C"
          value={readingForm.temperatureCelsius}
          onChange={(e) =>
            setReadingForm({
              ...readingForm,
              temperatureCelsius: e.target.value,
            })
          }
        />
        <input
          placeholder="Humidity %"
          value={readingForm.humidityPercent}
          onChange={(e) =>
            setReadingForm({ ...readingForm, humidityPercent: e.target.value })
          }
        />
        <button type="submit">Log reading</button>
      </form>
      <table
        width="100%"
        cellPadding={6}
        style={{ borderCollapse: "collapse", fontSize: 14 }}
      >
        <thead>
          <tr style={{ borderBottom: "1px solid #ccc", textAlign: "left" }}>
            <th>Temp</th>
            <th>Humidity</th>
            <th>When</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {readings.map((r) => (
            <tr
              key={r.id}
              style={{
                borderBottom: "1px solid #eee",
                background: r.violatesThreshold ? "#fff4f4" : "transparent",
              }}
            >
              <td>
                {r.temperatureCelsius != null
                  ? `${r.temperatureCelsius}°C`
                  : "—"}
              </td>
              <td>
                {r.humidityPercent != null ? `${r.humidityPercent}%` : "—"}
              </td>
              <td>{new Date(r.recordedAt).toLocaleString()}</td>
              <td>{r.violatesThreshold ? "⚠ out of range" : ""}</td>
            </tr>
          ))}
          {readings.length === 0 && (
            <tr>
              <td colSpan={4} style={{ color: "#888" }}>
                No sensor readings yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <h2 style={{ marginTop: 24 }}>Notification log</h2>
      <table
        width="100%"
        cellPadding={6}
        style={{ borderCollapse: "collapse", fontSize: 14 }}
      >
        <thead>
          <tr style={{ borderBottom: "1px solid #ccc", textAlign: "left" }}>
            <th>Status</th>
            <th>Recipient</th>
            <th>Subject</th>
            <th>When</th>
          </tr>
        </thead>
        <tbody>
          {notifications.map((n) => (
            <tr key={n.id} style={{ borderBottom: "1px solid #eee" }}>
              <td
                style={{
                  color:
                    n.status === "SENT"
                      ? "#137333"
                      : n.status === "FAILED"
                        ? "crimson"
                        : "#888",
                }}
              >
                {n.status.replace(/_/g, " ")}
              </td>
              <td>{n.recipient ?? "—"}</td>
              <td>{n.subject}</td>
              <td>{new Date(n.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {notifications.length === 0 && (
            <tr>
              <td colSpan={4} style={{ color: "#888" }}>
                No notifications yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </main>
  );
}
