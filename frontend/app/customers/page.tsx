"use client";

import Link from "next/link";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  ClientRecord,
  ManagementReport,
  Shipment,
  commercialApi,
  reportsApi,
  shipmentsApi,
} from "@/lib/api-client";

function today() {
  return new Date().toISOString().slice(0, 10);
}
function id() {
  return `AAL-CLI-${new Date().getFullYear()}-${Date.now().toString().slice(-7)}`;
}
function err(e: unknown) {
  return e instanceof ApiError || e instanceof Error
    ? e.message
    : "Unable to complete the request";
}

export default function CustomersPage() {
  const { accessToken, isLoading, role } = useAuth();
  const router = useRouter();
  const [clients, setClients] = useState<ClientRecord[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [report, setReport] = useState<ManagementReport | null>(null);
  const [selected, setSelected] = useState("");
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({
    clientId: id(),
    clientCompany: "",
    contactPerson: "",
    phone: "",
    email: "",
    industry: "",
    country: "Rwanda",
    city: "Kigali",
    leadSource: "",
    relationshipOwner: "",
    nextFollowUp: "",
    notes: "",
  });

  const canCreate = role === "ADMIN" || role === "MANAGER" || role === "SALES";
  const canReport =
    role === "ADMIN" || role === "MANAGER" || role === "FINANCE";

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  async function load() {
    if (!accessToken) return;
    setError("");
    try {
      const [c, s] = await Promise.all([
        commercialApi.clients(),
        shipmentsApi.list(),
      ]);
      setClients(c);
      setShipments(s.content);
      if (canReport) {
        const r = await reportsApi.management(
          "2000-01-01",
          new Date().toISOString().slice(0, 10),
        );
        setReport(r);
      } else setReport(null);
    } catch (e) {
      setError(err(e));
    }
  }
  useEffect(() => {
    void load();
  }, [accessToken, role]);

  async function createClient(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      const created = await commercialApi.createClient({
        ...form,
        nextFollowUp: form.nextFollowUp || null,
        clientStatus: "ACTIVE",
      });
      setClients((current) =>
        [...current, created].sort((a, b) =>
          (a.clientCompany || "").localeCompare(b.clientCompany || ""),
        ),
      );
      setForm({
        ...form,
        clientId: id(),
        clientCompany: "",
        contactPerson: "",
        phone: "",
        email: "",
        leadSource: "",
        relationshipOwner: "",
        nextFollowUp: "",
        notes: "",
      });
      setCreateOpen(false);
      setSuccess("Customer master record created.");
    } catch (e) {
      setError(err(e));
    } finally {
      setBusy(false);
    }
  }

  const filtered = useMemo(
    () =>
      clients.filter((c) =>
        [
          c.clientId,
          c.clientCompany,
          c.contactPerson,
          c.email,
          c.phone,
          c.country,
          c.city,
        ]
          .join(" ")
          .toLowerCase()
          .includes(query.toLowerCase()),
      ),
    [clients, query],
  );
  const selectedClient = clients.find(
    (c) => c.id === selected || c.clientId === selected,
  );
  const selectedShipments = selectedClient
    ? shipments.filter(
        (s) =>
          (s.clientName || "").trim().toLowerCase() ===
          (selectedClient.clientCompany || "").trim().toLowerCase(),
      )
    : [];
  const selectedProfit = canReport
    ? report?.customerProfitability.find(
        (x) =>
          x.customer.trim().toLowerCase() ===
          (selectedClient?.clientCompany || "").trim().toLowerCase(),
      )
    : null;

  if (isLoading || !accessToken) return null;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">CUSTOMER MANAGEMENT</div>
          <h1 className="page-title">Customer Master & 360</h1>
          <p className="page-subtitle">
            The single canonical customer record for contacts, shipment history,
            ownership and relationship follow-up.
          </p>
        </div>
        {canCreate && (
          <button
            className="btn btn-primary"
            onClick={() => setCreateOpen((v) => !v)}
          >
            {createOpen ? "Close form" : "Create customer"}
          </button>
        )}
      </div>
      {error && (
        <div className="alert alert-error" style={{ marginBottom: 12 }}>
          {error}
        </div>
      )}
      {success && (
        <div className="alert alert-success" style={{ marginBottom: 12 }}>
          {success}
        </div>
      )}
      {createOpen && canCreate && (
        <section className="card" style={{ marginTop: 14 }}>
          <h2 className="card-title">Create customer master record</h2>
          <form className="form-grid" onSubmit={createClient}>
            {(
              [
                ["clientId", "Client ID"],
                ["clientCompany", "Company"],
                ["contactPerson", "Contact person"],
                ["phone", "Phone"],
                ["email", "Email"],
                ["industry", "Industry"],
                ["country", "Country"],
                ["city", "City"],
                ["leadSource", "Lead source"],
                ["relationshipOwner", "Relationship owner"],
                ["nextFollowUp", "Next follow-up"],
              ] as const
            ).map(([k, l]) => (
              <div className="field" key={k}>
                <label>{l}</label>
                <input
                  type={
                    k === "nextFollowUp"
                      ? "date"
                      : k === "email"
                        ? "email"
                        : "text"
                  }
                  value={form[k]}
                  onChange={(e) => setForm({ ...form, [k]: e.target.value })}
                  required={k === "clientId" || k === "clientCompany"}
                />
              </div>
            ))}
            <div className="field field-span-2">
              <label>Notes</label>
              <textarea
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
              />
            </div>
            <button className="btn btn-primary" disabled={busy}>
              {busy ? "Saving…" : "Create customer"}
            </button>
          </form>
        </section>
      )}
      <section className="card" style={{ marginTop: 14 }}>
        <div className="page-head">
          <div>
            <h2 className="card-title">Customer register</h2>
            <div className="card-muted">
              Customer data is no longer duplicated in the commercial workspace.
            </div>
          </div>
          <input
            className="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search company, contact, email…"
          />
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Customer</th>
                <th>Contact</th>
                <th>Location</th>
                <th>Owner</th>
                <th>Status</th>
                <th>Next follow-up</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((c) => (
                <tr
                  key={c.id}
                  onClick={() => setSelected(c.id)}
                  style={{ cursor: "pointer" }}
                >
                  <td>
                    <strong>{c.clientCompany || c.clientId}</strong>
                    <small>{c.clientId}</small>
                  </td>
                  <td>
                    {c.contactPerson || "—"}
                    <small>{c.email || c.phone || ""}</small>
                  </td>
                  <td>
                    {[c.city, c.country].filter(Boolean).join(", ") || "—"}
                  </td>
                  <td>{c.relationshipOwner || "—"}</td>
                  <td>
                    <span className="status status-neutral">
                      {c.clientStatus || "—"}
                    </span>
                  </td>
                  <td>{c.nextFollowUp || "—"}</td>
                </tr>
              ))}
              {!filtered.length && (
                <tr>
                  <td colSpan={6} className="empty">
                    No customers found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      {selectedClient && (
        <section className="grid grid-3" style={{ marginTop: 14 }}>
          <div className="card">
            <div className="eyebrow">CUSTOMER</div>
            <h2 className="card-title">
              {selectedClient.clientCompany || selectedClient.clientId}
            </h2>
            <div className="stack-list">
              <div className="metric-row">
                <span>Contact</span>
                <strong>{selectedClient.contactPerson || "—"}</strong>
              </div>
              <div className="metric-row">
                <span>Email</span>
                <strong>{selectedClient.email || "—"}</strong>
              </div>
              <div className="metric-row">
                <span>Phone</span>
                <strong>{selectedClient.phone || "—"}</strong>
              </div>
              <div className="metric-row">
                <span>Location</span>
                <strong>
                  {[selectedClient.city, selectedClient.country]
                    .filter(Boolean)
                    .join(", ") || "—"}
                </strong>
              </div>
            </div>
          </div>
          <div className="card">
            <h2 className="card-title">Customer performance</h2>
            {canReport ? (
              <div className="stack-list">
                <div className="metric-row">
                  <span>Shipments</span>
                  <strong>
                    {selectedProfit?.shipments ?? selectedShipments.length}
                  </strong>
                </div>
                <div className="metric-row">
                  <span>Revenue</span>
                  <strong>
                    {selectedProfit?.revenue?.toLocaleString() ?? "—"}
                  </strong>
                </div>
                <div className="metric-row">
                  <span>Gross profit</span>
                  <strong>
                    {selectedProfit?.grossProfit?.toLocaleString() ?? "—"}
                  </strong>
                </div>
                <div className="metric-row">
                  <span>Margin</span>
                  <strong>
                    {selectedProfit
                      ? `${selectedProfit.marginPercent.toFixed(2)}%`
                      : "—"}
                  </strong>
                </div>
              </div>
            ) : (
              <div className="card-muted">
                Financial profitability is restricted to management and finance
                roles.
              </div>
            )}
          </div>
          <div className="card">
            <h2 className="card-title">Relationship control</h2>
            <p className="card-muted">
              Owner: {selectedClient.relationshipOwner || "Unassigned"}
            </p>
            <p className="card-muted">
              Next follow-up: {selectedClient.nextFollowUp || "Not scheduled"}
            </p>
            <Link className="btn btn-primary" href="/commercial">
              Open sales control
            </Link>
          </div>
        </section>
      )}
      {selectedClient && (
        <section className="card" style={{ marginTop: 14 }}>
          <div className="page-head">
            <div>
              <h2 className="card-title">Shipment history</h2>
              <div className="card-muted">
                Operational records linked to the canonical customer name.
              </div>
            </div>
          </div>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Route</th>
                  <th>Service</th>
                  <th>Revenue</th>
                  <th>Balance</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {selectedShipments.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <Link href={`/shipments/${s.id}`}>
                        <strong>{s.referenceCode}</strong>
                      </Link>
                    </td>
                    <td>
                      {s.originCityPort || s.originAddress} →{" "}
                      {s.destinationCityPort || s.destinationAddress}
                    </td>
                    <td>{s.serviceType || s.transportMode}</td>
                    <td>{s.amountBilledToClient?.toLocaleString() || "—"}</td>
                    <td>{s.amountRemaining?.toLocaleString() || "—"}</td>
                    <td>{s.status}</td>
                  </tr>
                ))}
                {!selectedShipments.length && (
                  <tr>
                    <td colSpan={6} className="empty">
                      No shipments linked to this customer.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}
