"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  airCargoApi,
  AirCargoBookingResponse,
  ApiError,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

function formatDate(value?: string | null): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(undefined, {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function statusClass(status?: string | null): string {
  const value = (status || "").toUpperCase();
  if (["CONFIRMED", "BOOKED", "BOOKING_CONFIRMED"].includes(value)) {
    return "status-success";
  }
  if (["FAILED", "CANCELLED", "REJECTED"].includes(value)) {
    return "status-error";
  }
  if (["UNKNOWN", "PENDING_PROVIDER", "REQUESTED", "PENDING"].includes(value)) {
    return "status-warning";
  }
  return "status-neutral";
}

function displayStatus(status?: string | null): string {
  return (status || "UNKNOWN").replaceAll("_", " ");
}

export default function AirCargoBookingsPage() {
  const { accessToken, isLoading } = useAuth();
  const [rows, setRows] = useState<AirCargoBookingResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionId, setActionId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [filter, setFilter] = useState("ALL");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<AirCargoBookingResponse | null>(
    null,
  );

  const load = async () => {
    if (!accessToken) return;
    setLoading(true);
    setError("");
    try {
      const result = await airCargoApi.bookings();
      setRows(result);
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : "Unable to load air cargo bookings.",
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!isLoading && accessToken) void load();
  }, [isLoading, accessToken]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter((row) => {
      const statusMatch =
        filter === "ALL" || (row.status || "").toUpperCase() === filter;
      if (!statusMatch) return false;
      if (!q) return true;
      return [
        row.flightNumber,
        row.carrierCode,
        row.carrierName,
        row.shipmentId,
        row.confirmationNumber,
        row.providerReference,
        row.provider,
        row.originCode,
        row.destinationCode,
      ].some((value) =>
        String(value || "")
          .toLowerCase()
          .includes(q),
      );
    });
  }, [rows, filter, search]);

  const summary = useMemo(() => {
    const active = rows.filter(
      (row) =>
        !["CANCELLED", "FAILED"].includes((row.status || "").toUpperCase()),
    );
    const confirmed = rows.filter((row) =>
      ["CONFIRMED", "BOOKED", "BOOKING_CONFIRMED"].includes(
        (row.status || "").toUpperCase(),
      ),
    );
    const pending = rows.filter((row) =>
      ["REQUESTED", "PENDING_PROVIDER", "PENDING", "UNKNOWN"].includes(
        (row.status || "").toUpperCase(),
      ),
    );
    const cancelled = rows.filter(
      (row) => (row.status || "").toUpperCase() === "CANCELLED",
    );
    return { active, confirmed, pending, cancelled };
  }, [rows]);

  async function cancelBooking(row: AirCargoBookingResponse) {
    if (row.status === "CANCELLED" || actionId) return;
    const reason = window
      .prompt("Cancellation reason", "Cancelled by AAL operations")
      ?.trim();
    if (!reason) return;

    setActionId(row.id);
    setError("");
    setMessage("");
    try {
      const updated = await airCargoApi.cancelBooking(row.id, {
        reason,
        idempotencyKey: `aal-ui-cancel-${row.id}-${Date.now()}`,
      });
      setRows((current) =>
        current.map((item) =>
          item.id === row.id ? { ...item, ...updated } : item,
        ),
      );
      setSelected((current) =>
        current?.id === row.id ? { ...current, ...updated } : current,
      );
      setMessage(`Booking ${row.flightNumber} was submitted for cancellation.`);
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to cancel the booking.",
      );
    } finally {
      setActionId(null);
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AIR CARGO · BOOKING CONTROL</div>
          <h1 className="page-title">Airline Booking Desk</h1>
          <p className="page-subtitle">
            Manage airline booking requests, provider references and operational
            status from one shipment-linked register.
          </p>
        </div>
        <div className="actions">
          <button
            className="btn"
            onClick={() => void load()}
            disabled={loading}
          >
            {loading ? "Refreshing…" : "Refresh"}
          </button>
          <Link className="btn btn-primary" href="/air-cargo">
            <Icon name="plane" /> Search capacity
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {message && <div className="alert alert-success">{message}</div>}

      <section className="grid grid-4" style={{ marginTop: 14 }}>
        <div className="card kpi">
          <div className="kpi-label">Total bookings</div>
          <div className="kpi-value">{rows.length.toLocaleString()}</div>
          <div className="card-muted">Tenant booking register</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Confirmed</div>
          <div className="kpi-value">
            {summary.confirmed.length.toLocaleString()}
          </div>
          <div className="card-muted">Carrier-confirmed or booked</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Pending / review</div>
          <div className="kpi-value">
            {summary.pending.length.toLocaleString()}
          </div>
          <div className="card-muted">Provider or reconciliation work</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Active</div>
          <div className="kpi-value">
            {summary.active.length.toLocaleString()}
          </div>
          <div className="card-muted">Not cancelled or failed</div>
        </div>
      </section>

      <section className="card" style={{ marginTop: 14 }}>
        <div className="page-head" style={{ marginBottom: 14 }}>
          <div>
            <div className="eyebrow">OPERATIONS REGISTER</div>
            <h2 className="card-title">Bookings & provider references</h2>
          </div>
          <span className="status status-neutral">{filtered.length} shown</span>
        </div>

        <div className="actions" style={{ marginBottom: 14 }}>
          <div className="field" style={{ minWidth: 280, flex: 1 }}>
            <label htmlFor="booking-search">Search</label>
            <input
              id="booking-search"
              className="input"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Flight, carrier, shipment, confirmation…"
            />
          </div>
          <div className="field" style={{ minWidth: 190 }}>
            <label htmlFor="booking-status">Status</label>
            <select
              id="booking-status"
              className="input"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
            >
              <option value="ALL">All statuses</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="REQUESTED">Requested</option>
              <option value="PENDING_PROVIDER">Pending provider</option>
              <option value="UNKNOWN">Unknown / reconcile</option>
              <option value="CANCELLED">Cancelled</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
        </div>

        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Flight / carrier</th>
                <th>Route</th>
                <th>Departure</th>
                <th>Status</th>
                <th>Reference</th>
                <th>Weight</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((row) => {
                const reference =
                  row.confirmationNumber || row.providerReference || "—";
                return (
                  <tr key={row.id}>
                    <td>
                      <strong>{row.flightNumber}</strong>
                      <div className="card-muted">
                        {row.carrierName ||
                          row.carrierCode ||
                          "Carrier pending"}
                      </div>
                    </td>
                    <td>
                      <strong>
                        {row.originCode} → {row.destinationCode}
                      </strong>
                      <div className="card-muted">
                        Shipment {row.shipmentId.slice(0, 8)}…
                      </div>
                    </td>
                    <td>{formatDate(row.departureTime)}</td>
                    <td>
                      <span className={`status ${statusClass(row.status)}`}>
                        {displayStatus(row.status)}
                      </span>
                    </td>
                    <td>
                      <strong>{reference}</strong>
                      <div className="card-muted">{row.provider || "AAL"}</div>
                    </td>
                    <td>{row.confirmedWeightKg ?? row.requestedWeightKg} kg</td>
                    <td>
                      <div
                        className="actions"
                        style={{ justifyContent: "flex-end" }}
                      >
                        <button
                          className="btn"
                          onClick={() => setSelected(row)}
                        >
                          View
                        </button>
                        <button
                          className="btn"
                          disabled={
                            row.status === "CANCELLED" || actionId === row.id
                          }
                          onClick={() => void cancelBooking(row)}
                        >
                          {actionId === row.id ? "Working…" : "Cancel"}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
              {!filtered.length && (
                <tr>
                  <td colSpan={7} className="empty">
                    {loading
                      ? "Loading airline bookings…"
                      : "No bookings match the current filters."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {selected && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Booking details"
          style={{
            position: "fixed",
            inset: 0,
            zIndex: 1000,
            background: "rgba(3, 10, 24, 0.62)",
            display: "flex",
            justifyContent: "flex-end",
          }}
          onClick={(event) => {
            if (event.target === event.currentTarget) setSelected(null);
          }}
        >
          <aside
            style={{
              width: "min(520px, 100%)",
              height: "100%",
              overflowY: "auto",
              background: "var(--surface, #fff)",
              padding: 28,
              boxShadow: "-20px 0 60px rgba(0,0,0,.18)",
            }}
          >
            <div className="page-head" style={{ marginBottom: 20 }}>
              <div>
                <div className="eyebrow">BOOKING DETAIL</div>
                <h2 className="card-title">{selected.flightNumber}</h2>
                <div className="card-muted">
                  {selected.carrierName ||
                    selected.carrierCode ||
                    "Carrier pending"}
                </div>
              </div>
              <button className="btn" onClick={() => setSelected(null)}>
                Close
              </button>
            </div>

            <div className="metric-row">
              <span>Status</span>
              <strong>{displayStatus(selected.status)}</strong>
            </div>
            <div className="metric-row">
              <span>Route</span>
              <strong>
                {selected.originCode} → {selected.destinationCode}
              </strong>
            </div>
            <div className="metric-row">
              <span>Departure</span>
              <strong>{formatDate(selected.departureTime)}</strong>
            </div>
            <div className="metric-row">
              <span>Arrival</span>
              <strong>{formatDate(selected.arrivalTime)}</strong>
            </div>
            <div className="metric-row">
              <span>Requested weight</span>
              <strong>{selected.requestedWeightKg} kg</strong>
            </div>
            <div className="metric-row">
              <span>Confirmed weight</span>
              <strong>{selected.confirmedWeightKg ?? "—"} kg</strong>
            </div>
            <div className="metric-row">
              <span>Service level</span>
              <strong>{selected.serviceLevel || "STANDARD"}</strong>
            </div>
            <div className="metric-row">
              <span>Provider</span>
              <strong>{selected.provider || "AAL internal"}</strong>
            </div>
            <div className="metric-row">
              <span>Provider reference</span>
              <strong>{selected.providerReference || "—"}</strong>
            </div>
            <div className="metric-row">
              <span>Confirmation</span>
              <strong>{selected.confirmationNumber || "—"}</strong>
            </div>
            <div className="metric-row">
              <span>Shipment</span>
              <strong>{selected.shipmentId}</strong>
            </div>

            {selected.cancellationReason && (
              <div className="alert" style={{ marginTop: 18 }}>
                Cancellation reason: {selected.cancellationReason}
              </div>
            )}

            <div className="actions" style={{ marginTop: 22 }}>
              <Link className="btn" href={`/shipments/${selected.shipmentId}`}>
                Open shipment
              </Link>
              <button
                className="btn"
                disabled={
                  selected.status === "CANCELLED" || actionId === selected.id
                }
                onClick={() => void cancelBooking(selected)}
              >
                Cancel booking
              </button>
            </div>
          </aside>
        </div>
      )}
    </main>
  );
}
