"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  airCargoApi,
  AirCargoFlight,
  ApiError,
  RouteOption,
  Shipment,
  shipmentsApi,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

function dateRange(date: string): { from: string; to: string } {
  const start = new Date(`${date}T00:00:00.000Z`);
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return { from: start.toISOString(), to: end.toISOString() };
}

function text(value: unknown): string {
  return value == null ? "—" : String(value);
}

export default function AirCargoPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();

  const [origin, setOrigin] = useState("NBO");
  const [destination, setDestination] = useState("KGL");
  const [weight, setWeight] = useState("100");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [flights, setFlights] = useState<AirCargoFlight[]>([]);
  const [routes, setRoutes] = useState<RouteOption[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [shipmentId, setShipmentId] = useState("");
  const [selected, setSelected] = useState<AirCargoFlight | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [booking, setBooking] = useState(false);

  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (!accessToken) return;
    shipmentsApi
      .list()
      .then((response) =>
        setShipments(
          response.content.filter(
            (shipment) =>
              shipment.transportMode === "AIR" ||
              shipment.transportMode === "ROAD",
          ),
        ),
      )
      .catch(() => undefined);
  }, [accessToken]);

  const selectedShipment = useMemo(
    () => shipments.find((shipment) => shipment.id === shipmentId) ?? null,
    [shipments, shipmentId],
  );

  if (isLoading || !accessToken) return null;

  async function search() {
    const requestedWeight = Number(weight);
    const from = origin.trim().toUpperCase();
    const to = destination.trim().toUpperCase();

    if (!/^[A-Z]{3}$/.test(from) || !/^[A-Z]{3}$/.test(to)) {
      setError(
        "Origin and destination must be valid three-letter airport codes.",
      );
      return;
    }
    if (from === to) {
      setError("Origin and destination must be different.");
      return;
    }
    if (!Number.isFinite(requestedWeight) || requestedWeight <= 0) {
      setError("Chargeable weight must be greater than zero.");
      return;
    }

    setLoading(true);
    setError("");
    setMessage("");
    setSelected(null);

    try {
      const range = dateRange(date);
      const request = {
        origin: from,
        destination: to,
        weightKg: requestedWeight,
        ...range,
      };
      const [flightResults, routeResults] = await Promise.all([
        airCargoApi.searchFlights(request),
        airCargoApi.optimizeRoutes(request),
      ]);
      setFlights(flightResults);
      setRoutes(routeResults);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Air cargo search failed");
    } finally {
      setLoading(false);
    }
  }

  async function book() {
    if (!selected || !shipmentId) {
      setError("Select a shipment and a flight before booking.");
      return;
    }

    const requestedWeight = Number(weight);
    if (!Number.isFinite(requestedWeight) || requestedWeight <= 0) {
      setError("Chargeable weight must be greater than zero.");
      return;
    }

    setBooking(true);
    setError("");
    setMessage("");

    try {
      const result = await airCargoApi.book({
        shipmentId,
        carrierCode: selected.carrierCode,
        carrierName: selected.carrierName,
        flightNumber: selected.flightNumber,
        departureTime: selected.departure,
        arrivalTime: selected.arrival ?? undefined,
        originCode: selected.origin,
        destinationCode: selected.destination,
        weightKg: requestedWeight,
        idempotencyKey: `AAL-AIR-${shipmentId}-${selected.id}-${requestedWeight}`,
      });

      setMessage(
        `Booking ${result.status.toLowerCase()}${
          result.confirmationNumber ? ` · ${result.confirmationNumber}` : ""
        }.`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Booking failed");
    } finally {
      setBooking(false);
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Air freight intelligence</div>
          <h1 className="page-title">Capacity & booking desk</h1>
          <p className="page-subtitle">
            Compare persisted carrier capacity, optimize available routes and
            move into an idempotent shipment-linked booking.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/shipments">
            <Icon name="ship" size={15} /> Shipment ledger
          </Link>
          <Link className="btn" href="/command-center">
            <Icon name="file" size={15} /> Commercial
          </Link>
        </div>
      </div>

      {error && (
        <div className="alert alert-error" style={{ marginBottom: 12 }}>
          {error}
        </div>
      )}
      {message && (
        <div className="alert alert-success" style={{ marginBottom: 12 }}>
          {message}
        </div>
      )}

      <section className="card" style={{ padding: 20 }}>
        <div className="grid grid-4">
          <div className="field">
            <label>Origin airport</label>
            <input
              value={origin}
              onChange={(e) => setOrigin(e.target.value.toUpperCase())}
              maxLength={3}
              placeholder="KGL"
            />
          </div>
          <div className="field">
            <label>Destination airport</label>
            <input
              value={destination}
              onChange={(e) => setDestination(e.target.value.toUpperCase())}
              maxLength={3}
              placeholder="AMS"
            />
          </div>
          <div className="field">
            <label>Chargeable weight (kg)</label>
            <input
              type="number"
              min="0.1"
              step="0.001"
              value={weight}
              onChange={(e) => setWeight(e.target.value)}
            />
          </div>
          <div className="field">
            <label>Departure date</label>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </div>
        </div>
        <div
          style={{ display: "flex", justifyContent: "flex-end", marginTop: 13 }}
        >
          <button
            className="btn btn-primary"
            onClick={search}
            disabled={loading}
          >
            <Icon name="search" size={15} />{" "}
            {loading ? "Searching…" : "Search capacity"}
          </button>
        </div>
      </section>

      <div className="grid grid-3" style={{ marginTop: 15 }}>
        <section className="card" style={{ gridColumn: "span 2" }}>
          <div className="page-head" style={{ marginBottom: 10 }}>
            <div>
              <h2 className="card-title">Carrier options</h2>
              <div className="card-muted">
                Only persisted/configured provider data is displayed.
              </div>
            </div>
            <span className="status status-success">Capacity protected</span>
          </div>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Carrier</th>
                  <th>Flight</th>
                  <th>Departure</th>
                  <th>Arrival</th>
                  <th>Available</th>
                  <th>Source</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {flights.map((flight) => (
                  <tr
                    key={flight.id}
                    style={{
                      background:
                        selected?.id === flight.id ? "#f5f9ff" : undefined,
                    }}
                  >
                    <td>
                      <strong>
                        {flight.carrierName || flight.carrierCode}
                      </strong>
                    </td>
                    <td>{flight.flightNumber}</td>
                    <td>{text(flight.departure)}</td>
                    <td>{text(flight.arrival)}</td>
                    <td>
                      {flight.availableCapacityKg.toLocaleString()} /{" "}
                      {flight.totalCapacityKg.toLocaleString()} kg
                    </td>
                    <td>{flight.source || "—"}</td>
                    <td>
                      <button
                        className={`btn ${selected?.id === flight.id ? "btn-primary" : ""}`}
                        onClick={() => setSelected(flight)}
                      >
                        {selected?.id === flight.id ? "Selected" : "Select"}
                      </button>
                    </td>
                  </tr>
                ))}
                {!flights.length && (
                  <tr>
                    <td colSpan={7} className="empty">
                      Search a lane and date to compare available capacity.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section className="card">
          <h2 className="card-title">Secure this capacity</h2>
          <div className="card-muted" style={{ margin: "4px 0 12px" }}>
            The server reserves capacity transactionally and enforces the
            idempotency key.
          </div>
          <div className="field">
            <label>Shipment</label>
            <select
              value={shipmentId}
              onChange={(e) => setShipmentId(e.target.value)}
            >
              <option value="">Select shipment</option>
              {shipments.map((shipment) => (
                <option value={shipment.id} key={shipment.id}>
                  {shipment.referenceCode} · {shipment.clientName || "Client"}
                </option>
              ))}
            </select>
          </div>
          {selectedShipment && (
            <div className="quick" style={{ marginTop: 12 }}>
              <strong>{selectedShipment.referenceCode}</strong>
              <span>{selectedShipment.clientName || "Client"}</span>
            </div>
          )}
          {selected && (
            <div
              style={{
                marginTop: 12,
                padding: 12,
                borderRadius: 9,
                background: "#f8fafc",
                border: "1px solid var(--line)",
                fontSize: 11,
              }}
            >
              <strong>
                {selected.carrierName} {selected.flightNumber}
              </strong>
              <div className="card-muted" style={{ marginTop: 4 }}>
                {selected.origin} → {selected.destination}
              </div>
              <div className="card-muted">
                Available {selected.availableCapacityKg.toLocaleString()} kg
              </div>
            </div>
          )}
          <button
            className="btn btn-primary"
            style={{ width: "100%", justifyContent: "center", marginTop: 12 }}
            disabled={!selected || !shipmentId || booking}
            onClick={book}
          >
            <Icon name="plane" size={15} />{" "}
            {booking ? "Submitting…" : "Request booking"}
          </button>
        </section>
      </div>

      <section className="card" style={{ marginTop: 15 }}>
        <div className="page-head" style={{ marginBottom: 8 }}>
          <div>
            <h2 className="card-title">Recommended routes</h2>
            <div className="card-muted">
              Direct and one-connection options scored by transit time, capacity
              scarcity and connection penalty.
            </div>
          </div>
          <span className="status status-neutral">Decision support</span>
        </div>
        <div className="grid grid-3">
          {routes.slice(0, 6).map((route) => (
            <div
              className="quick"
              key={`${route.flightId}-${route.flightNumber}`}
            >
              <span>{route.carrierName || route.carrierCode}</span>
              <strong>{route.flightNumber}</strong>
              <div className="card-muted" style={{ marginTop: 5 }}>
                {route.origin} → {route.destination}
              </div>
              <div className="card-muted">
                Score {route.score.toFixed(1)} · Capacity{" "}
                {route.availableCapacityKg.toLocaleString()} kg
              </div>
            </div>
          ))}
          {!routes.length && (
            <div className="empty" style={{ gridColumn: "1/-1" }}>
              Route recommendations appear after a search.
            </div>
          )}
        </div>
      </section>
    </main>
  );
}
