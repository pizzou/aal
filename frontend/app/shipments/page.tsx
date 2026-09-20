"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { ApiError, Shipment, shipmentsApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

const MODES = ["ALL", "AIR", "ROAD", "SEA", "RAIL"];
const STATUSES = [
  "ALL",
  "PLANNING",
  "BOOKED",
  "IN_TRANSIT",
  "ARRIVED",
  "CUSTOMS",
  "DELIVERED",
  "COMPLETED",
  "ON_HOLD",
];

function money(value: number | null | undefined, currency = "USD"): string {
  if (value == null) {
    return "—";
  }

  return `${currency} ${value.toLocaleString(undefined, {
    maximumFractionDigits: 2,
  })}`;
}

function statusClass(status: string): string {
  if (status === "DELIVERED" || status === "COMPLETED") {
    return "status status-success";
  }

  if (status === "ON_HOLD" || status === "CUSTOMS") {
    return "status status-warning";
  }

  if (status === "CANCELLED") {
    return "status status-danger";
  }

  return "status status-neutral";
}

export default function ShipmentsPage() {
  const router = useRouter();

  const { accessToken, isLoading } = useAuth();

  const [shipments, setShipments] = useState<Shipment[]>([]);

  const [query, setQuery] = useState("");

  const [mode, setMode] = useState("ALL");
  const [status, setStatus] = useState("ALL");
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [error, setError] = useState("");

  useEffect(() => {
    if (!isLoading && !accessToken) {
      router.push("/login");
    }
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (!accessToken) return;

    const timer = window.setTimeout(() => {
      let active = true;
      setError("");

      shipmentsApi
        .list({ page, size: 50, q: query.trim(), mode, status })
        .then((response) => {
          if (!active) return;
          setShipments(response.content);
          setTotalElements(response.totalElements);
          setTotalPages(response.totalPages);
        })
        .catch((exception) => {
          if (!active) return;
          setError(
            exception instanceof ApiError
              ? exception.message
              : "Failed to load shipments.",
          );
        });

      return () => {
        active = false;
      };
    }, 250);

    return () => window.clearTimeout(timer);
  }, [accessToken, page, query, mode, status]);

  useEffect(() => {
    setPage(0);
  }, [query, mode, status]);

  const rows = useMemo(() => shipments, [shipments]);

  if (isLoading || !accessToken) {
    return null;
  }

  const grossWeight = rows.reduce(
    (sum, shipment) => sum + (shipment.grossWeightKg ?? 0),
    0,
  );

  const chargeableWeight = rows.reduce(
    (sum, shipment) => sum + (shipment.chargeableWeightKg ?? 0),
    0,
  );

  const billed = rows.reduce(
    (sum, shipment) => sum + (shipment.amountBilledToClient ?? 0),
    0,
  );

  const outstanding = rows.reduce(
    (sum, shipment) => sum + (shipment.amountRemaining ?? 0),
    0,
  );

  const activeShipments = rows.filter(
    (shipment) =>
      !["DELIVERED", "COMPLETED", "CANCELLED"].includes(shipment.status),
  ).length;

  const deliveredShipments = rows.filter(
    (shipment) =>
      shipment.status === "DELIVERED" || shipment.status === "COMPLETED",
  ).length;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / OPERATIONS REGISTER</div>

          <h1 className="page-title">Shipments</h1>

          <p className="page-subtitle">
            The digital daily shipment ledger for Aviation Africa Logistics Ltd
            — operational, cargo and financial information in one controlled
            record.
          </p>
        </div>

        <div className="actions">
          <Link className="btn" href="/billing">
            Billing & receivables
          </Link>

          <Link className="btn btn-primary" href="/new-shipment">
            + New shipment
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      <section className="register-overview">
        <div className="register-overview-copy">
          <span>AAL DAILY OPERATING LEDGER</span>

          <h2>Every shipment. Every kilogram. Every franc.</h2>

          <p>
            This register brings the MOTHERSHIP spreadsheet workflow into a
            searchable operational system.
          </p>
        </div>

        <div className="register-overview-stats">
          <div>
            <span>RECORDS</span>
            <strong>{totalElements.toLocaleString()}</strong>
          </div>

          <div>
            <span>GROSS KG</span>
            <strong>{grossWeight.toLocaleString()}</strong>
          </div>

          <div>
            <span>CHARGEABLE KG</span>
            <strong>{chargeableWeight.toLocaleString()}</strong>
          </div>
        </div>
      </section>

      <div className="grid grid-4 shipment-kpis">
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Active shipments</div>

          <div className="kpi-value">{activeShipments}</div>

          <div className="kpi-meta">Currently in execution</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Client billing</div>

          <div className="kpi-value">{money(billed)}</div>

          <div className="kpi-meta">Amount billed across register</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Outstanding</div>

          <div className="kpi-value">{money(outstanding)}</div>

          <div className="kpi-meta">Customer balances</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Delivered</div>

          <div className="kpi-value">{deliveredShipments}</div>

          <div className="kpi-meta">Completed movements</div>
        </div>
      </div>

      <section className="card shipment-register-card">
        <div className="register-head">
          <div>
            <div className="eyebrow">DAILY SHIPMENT LEDGER</div>

            <h2 className="card-title">AAL shipment records</h2>

            <p className="card-muted">
              Search AWB/reference, client, commodity, airline, invoice or
              route.
            </p>
          </div>

          <div className="register-tools">
            <input
              className="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search shipment, client, AWB, invoice…"
              aria-label="Search shipments"
            />

            <div className="filter-group">
              {MODES.map((item) => (
                <button
                  key={item}
                  type="button"
                  className={
                    mode === item ? "filter-button active" : "filter-button"
                  }
                  onClick={() => setMode(item)}
                >
                  {item}
                </button>
              ))}
            </div>
            <select
              className="input"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
              aria-label="Filter shipment status"
            >
              {STATUSES.map((item) => (
                <option key={item} value={item}>
                  {item === "ALL" ? "All statuses" : item.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="table-wrap">
          <table className="table shipment-table">
            <thead>
              <tr>
                <th>Shipment / AWB</th>
                <th>Client</th>
                <th>Route</th>
                <th>Commodity</th>
                <th>Gross kg</th>
                <th>Volumetric kg</th>
                <th>Chargeable kg</th>
                <th>Billed</th>
                <th>Balance</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>

            <tbody>
              {rows.map((shipment) => (
                <tr key={shipment.id}>
                  <td>
                    <Link
                      href={`/shipments/${shipment.id}`}
                      className="table-link strong-link"
                    >
                      {shipment.referenceCode}
                    </Link>

                    <div className="card-muted">
                      {shipment.invoiceNo ||
                        shipment.airlineUsed ||
                        "No invoice"}
                    </div>
                  </td>

                  <td>
                    <strong>{shipment.clientName || "—"}</strong>

                    <div className="card-muted">{shipment.contact || ""}</div>
                  </td>

                  <td>
                    <div className="route-cell">
                      <span>
                        {shipment.originCityPort ||
                          shipment.originAddress ||
                          "Origin"}
                      </span>

                      <IconArrow />

                      <span>
                        {shipment.destinationCityPort ||
                          shipment.destinationAddress ||
                          "Destination"}
                      </span>
                    </div>
                  </td>

                  <td>{shipment.commodity || "—"}</td>

                  <td>{shipment.grossWeightKg ?? "—"}</td>

                  <td>{shipment.volumetricWeightKg ?? "—"}</td>

                  <td>
                    <strong>
                      {shipment.chargeableWeightKg ?? shipment.weightKg ?? "—"}
                    </strong>
                  </td>

                  <td>
                    {money(
                      shipment.amountBilledToClient,
                      shipment.currency || "USD",
                    )}
                  </td>

                  <td>
                    <strong>
                      {money(
                        shipment.amountRemaining,
                        shipment.currency || "USD",
                      )}
                    </strong>
                  </td>

                  <td>
                    <span className={statusClass(shipment.status)}>
                      {shipment.status.replaceAll("_", " ")}
                    </span>
                  </td>

                  <td>
                    <Link
                      className="btn btn-small"
                      href={`/shipments/${shipment.id}`}
                    >
                      Open
                    </Link>
                  </td>
                </tr>
              ))}

              {!rows.length && (
                <tr>
                  <td colSpan={11} className="empty">
                    No AAL shipment records match the selected filters.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="page-head" style={{ marginTop: 14, marginBottom: 0 }}>
          <div className="card-muted">
            Showing {rows.length ? page * 50 + 1 : 0}–
            {Math.min(page * 50 + rows.length, totalElements)} of{" "}
            {totalElements.toLocaleString()} shipments
          </div>
          <div className="actions">
            <button
              className="btn btn-small"
              type="button"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </button>
            <span className="card-muted">
              Page {totalPages ? page + 1 : 0} / {totalPages || 0}
            </span>
            <button
              className="btn btn-small"
              type="button"
              disabled={page + 1 >= totalPages}
              onClick={() =>
                setPage((current) => Math.min(totalPages - 1, current + 1))
              }
            >
              Next
            </button>
          </div>
        </div>
      </section>
    </main>
  );
}

function IconArrow() {
  return <span className="route-arrow-mini">→</span>;
}
